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
 * AGF-S2-26 (D-S2-26-2): V5 adds business.time_zone VARCHAR(64) NOT NULL DEFAULT 'Africa/Addis_Ababa'. Every existing
 * restaurant is Ethiopian, so it keeps Addis Ababa time (D3) with all its data untouched.
 */
class BusinessTimeZoneMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "timezone_dev";

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
    void anExistingDatabaseKeepsAddisAbabaTimeAndAllItsData() {
        Map<String, Object> before = jdbc.queryForMap("select * from " + DEV + ".business where id = 'b1'");

        Flyway.configure().dataSource(dataSource).schemas(DEV).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1").load().migrate();

        assertThat(jdbc.queryForList("select version from " + DEV + ".flyway_schema_history order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
        Map<String, Object> after = jdbc.queryForMap("select * from " + DEV + ".business where id = 'b1'");
        assertThat(after.get("time_zone")).isEqualTo("Africa/Addis_Ababa");
        after.remove("time_zone");
        after.remove("country");
        after.remove("location_url");
        assertThat(after).isEqualTo(before);
    }

    @Test
    void theColumnIsARequiredZoneDefaultingToAddisAbaba() {
        Flyway.configure().dataSource(dataSource).schemas(DEV).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1").load().migrate();
        String q = "select data_type, character_maximum_length, is_nullable, column_default from information_schema.columns "
                + "where table_schema = ? and table_name = 'business' and column_name = 'time_zone'";
        for (String schema : List.of("public", DEV)) {
            Map<String, Object> col = jdbc.queryForMap(q, schema);
            assertThat(col).containsEntry("data_type", "character varying").containsEntry("character_maximum_length", 64)
                    .containsEntry("is_nullable", "NO");
            assertThat((String) col.get("column_default")).startsWith("'Africa/Addis_Ababa'");
        }
    }
}
