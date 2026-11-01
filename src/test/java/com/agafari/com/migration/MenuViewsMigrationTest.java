package com.agafari.com.migration;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF-S2-24 (AC11): V9 adds the menu_view_daily aggregate table (one row per restaurant and day, deleted with the
 * restaurant). Fresh and existing databases get the same table; existing data is untouched.
 */
class MenuViewsMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "menuviews_dev";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void existingDatabaseWithARestaurant() throws Exception {
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
                    + "custom_subdomain, currency, open24_7, \"createdAt\", \"updatedAt\") values ('b1', 'u1', 'Dev Cafe', '+251911', "
                    + "'Bole Road 1', 'Addis Ababa', 'AA', '', 'dev', 'ETB', true, now(), now())");
            c.createStatement().execute("set search_path to public");
        }
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
    }

    private void migrateDev() {
        Flyway.configure().dataSource(dataSource).schemas(DEV).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1").load().migrate();
    }

    @Test
    void anExistingDatabaseGetsTheEmptyTableAndKeepsAllItsData() {
        Map<String, Object> before = jdbc.queryForMap("select * from " + DEV + ".business where id = 'b1'");

        migrateDev();

        assertThat(jdbc.queryForList("select version from " + DEV + ".flyway_schema_history order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13");
        assertThat(jdbc.queryForObject("select count(*) from " + DEV + ".menu_view_daily", Integer.class)).isZero();
        Map<String, Object> after = jdbc.queryForMap("select * from " + DEV + ".business where id = 'b1'");
        for (String column : new String[]{"location_url", "country", "time_zone"}) after.remove(column);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void theTableHasItsKeysAndConstraintsOnFreshAndUpgradedDatabases() {
        migrateDev();
        String q = "select constraint_name from information_schema.table_constraints where table_schema = ? and table_name = 'menu_view_daily'";
        for (String schema : List.of("public", DEV)) {
            assertThat(jdbc.queryForList(q, String.class, schema)).as(schema)
                    .contains("pk_menu_view_daily", "fk_menu_view_daily_business", "ck_menu_view_daily_count");
            assertThat(jdbc.queryForObject("select delete_rule from information_schema.referential_constraints where constraint_schema = ? "
                    + "and constraint_name = 'fk_menu_view_daily_business'", String.class, schema)).isEqualTo("CASCADE");
            assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema = ? "
                    + "and table_name = 'menu_view_daily' order by column_name", String.class, schema))
                    .containsExactly("business_id", "view_count", "view_date");
        }
    }

    @Test
    void oneRowPerRestaurantAndDayNoNegativeCountsAndRowsGoWithTheRestaurant() {
        migrateDev();
        String insert = "insert into " + DEV + ".menu_view_daily (business_id, view_date, view_count) values ('b1', date '2026-11-01', ?)";
        jdbc.update(insert, 4);
        assertThatThrownBy(() -> jdbc.update(insert, 1)).hasMessageContaining("pk_menu_view_daily");
        assertThatThrownBy(() -> jdbc.update("insert into " + DEV + ".menu_view_daily (business_id, view_date, view_count) "
                + "values ('b1', date '2026-11-02', -1)")).hasMessageContaining("ck_menu_view_daily_count");

        jdbc.update("delete from " + DEV + ".business where id = 'b1'");
        assertThat(jdbc.queryForObject("select count(*) from " + DEV + ".menu_view_daily", Integer.class)).isZero();
    }
}
