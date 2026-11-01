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

/**
 * AGF-S2-36 (D-CS-2): V8 adds the category_special table (one per category, deleted with the category, restaurant
 * must match the category's). Existing data, including the special table, is untouched.
 */
class CategorySpecialsMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "catspecials_dev";

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
                    + "custom_subdomain, currency, open24_7, logo_key, \"createdAt\", \"updatedAt\") values ('b1', 'u1', 'Dev Cafe', '+251911', "
                    + "'Bole Road 1', 'Addis Ababa', 'AA', '', 'dev', 'ETB', true, 'agafari/logos/b1/logo.png', now(), now())");
            c.createStatement().execute("set search_path to public");
        }
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
    }

    @Test
    void anExistingDatabaseGetsTheEmptyCategorySpecialTableAndKeepsAllItsData() {
        Map<String, Object> before = jdbc.queryForMap("select * from " + DEV + ".business where id = 'b1'");

        Flyway.configure().dataSource(dataSource).schemas(DEV).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1").load().migrate();

        assertThat(jdbc.queryForList("select version from " + DEV + ".flyway_schema_history order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");
        assertThat(jdbc.queryForObject("select count(*) from " + DEV + ".category_special", Integer.class)).isZero();
        Map<String, Object> after = jdbc.queryForMap("select * from " + DEV + ".business where id = 'b1'");
        for (String column : new String[]{"location_url", "country", "time_zone"}) after.remove(column);
        assertThat(after).isEqualTo(before);
    }

    @Test
    void theTableHasItsKeysAndConstraintsOnFreshAndUpgradedDatabases() {
        Flyway.configure().dataSource(dataSource).schemas(DEV).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1").load().migrate();
        String q = "select constraint_name from information_schema.table_constraints where table_schema = ? and table_name = 'category_special'";
        for (String schema : List.of("public", DEV)) {
            assertThat(jdbc.queryForList(q, String.class, schema)).as(schema)
                    .contains("pk_category_special", "fk_category_special_business", "fk_category_special_category", "uk_category_special_category", "ck_category_special_percent");
            assertThat(jdbc.queryForObject("select count(*) from information_schema.triggers where event_object_schema = ? "
                    + "and event_object_table = 'category_special' and trigger_name = 'category_special_business_matches_category'", Integer.class, schema)).isPositive();
            assertThat(jdbc.queryForObject("select delete_rule from information_schema.referential_constraints where constraint_schema = ? "
                    + "and constraint_name = 'fk_category_special_category'", String.class, schema)).isEqualTo("CASCADE");
        }
    }
}
