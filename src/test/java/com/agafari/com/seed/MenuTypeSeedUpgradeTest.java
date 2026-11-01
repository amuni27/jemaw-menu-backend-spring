package com.agafari.com.seed;

import com.agafari.com.support.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF-S1-09 on an existing development database (baselined at V1): V2 adds only the missing types, case-insensitively,
 * never touching existing rows or the menus that use them, and running it again changes nothing.
 */
class MenuTypeSeedUpgradeTest extends AbstractIntegrationTest {

    private static final String DEV = "seed_dev";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void existingDatabaseWithALowerCaseLunchAndAMenuUsingIt() throws Exception {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
        jdbc.execute("create schema " + DEV);
        String ddl = new ClassPathResource("db/legacy/ddl-auto-schema.sql").getContentAsString(StandardCharsets.UTF_8);
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("set search_path to " + DEV);
            for (String statement : ddl.split(";")) {
                String sql = statement.lines().filter(l -> !l.strip().startsWith("--")).reduce("", (a, b) -> a + "\n" + b).strip();
                if (!sql.isEmpty()) c.createStatement().execute(sql);
            }
            c.createStatement().execute("insert into users (id, email, full_name, password_hash, role, status, \"createdAt\", \"updatedAt\") "
                    + "values ('u1', 'dev@test.local', 'Dev', 'x', 'VENUE', 'ACTIVE', now(), now())");
            c.createStatement().execute("insert into business (id, owner_user_id, name, business_phone, street_address, city, state, zipcode, "
                    + "custom_subdomain, currency, open24_7, \"createdAt\", \"updatedAt\") values ('b1', 'u1', 'Dev Cafe', '+251', '1 St', "
                    + "'Addis Ababa', 'AA', '', 'dev', 'ETB', true, now(), now())");
            c.createStatement().execute("insert into menu_type (id, name, \"createdAt\", \"updatedAt\") values ('legacy-lunch', 'lunch', now(), now())");
            c.createStatement().execute("insert into menu (id, business_id, menu_type_id, name, is_active, visibility, \"createdAt\", \"updatedAt\") "
                    + "values ('m1', 'b1', 'legacy-lunch', 'Old Lunch Menu', true, 'PUBLIC', now(), now())");
            c.createStatement().execute("set search_path to public");
        }
        Flyway.configure().dataSource(dataSource).schemas(DEV).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1").target("1").load().migrate(); // baselined at V1 only
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
    }

    private void migrateToLatest() {
        Flyway.configure().dataSource(dataSource).schemas(DEV).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1").load().migrate();
    }

    // AC2, AC3
    @Test
    void anExistingLowerCaseLunchIsKeptAndNotDuplicatedAndItsMenuStillResolves() {
        migrateToLatest();

        List<Map<String, Object>> lunches = jdbc.queryForList("select id, name from " + DEV + ".menu_type where lower(name) = 'lunch'");
        assertThat(lunches).hasSize(1);
        assertThat(lunches.get(0)).containsEntry("id", "legacy-lunch").containsEntry("name", "lunch");

        assertThat(jdbc.queryForObject("select count(*) from " + DEV + ".menu_type", Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("select t.name from " + DEV + ".menu m join " + DEV + ".menu_type t on t.id = m.menu_type_id where m.id = 'm1'",
                String.class)).isEqualTo("lunch");
        assertThat(jdbc.queryForList("select version from " + DEV + ".flyway_schema_history order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"); // V3 (AGF-S2-12), V4 (CR1), V5 (S2-26), V6 (S2-23), V7 (S2-25), V8 (S2-36), V9 (S2-24), V10 (S2-22) and V11 (E4-M1) follow the seed
    }

    // AC4
    @Test
    void runningTheSeedAgainChangesNothing() throws Exception {
        migrateToLatest();
        migrateToLatest();
        String v2 = new ClassPathResource("db/migration/V2__seed_menu_types.sql").getContentAsString(StandardCharsets.UTF_8);
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("set search_path to " + DEV);
            int inserted = c.createStatement().executeUpdate(v2.lines().filter(l -> !l.strip().startsWith("--"))
                    .reduce("", (a, b) -> a + "\n" + b).strip().replaceAll(";\\s*$", ""));
            c.createStatement().execute("set search_path to public");
            assertThat(inserted).isZero();
        }
        assertThat(jdbc.queryForObject("select count(*) from " + DEV + ".menu_type", Integer.class)).isEqualTo(9);
    }
}
