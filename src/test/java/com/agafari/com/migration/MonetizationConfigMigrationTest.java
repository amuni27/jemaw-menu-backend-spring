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
 * AGF E4-M2 (AC12): V12 adds the versioned, append-only monetization_config table (setting/model check, a foreign key to
 * the admin who saved it, a trigger that rejects UPDATE and DELETE). It starts empty ("not configured", D-M2-1). Fresh
 * and existing databases get the same objects; existing data is untouched.
 */
class MonetizationConfigMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "monetization_dev";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void existingDatabaseWithAnOwner() throws Exception {
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
            c.createStatement().execute("set search_path to public");
        }
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
    }

    private void migrate() {
        Flyway.configure().dataSource(dataSource).schemas(DEV).locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("1").load().migrate();
    }

    @Test
    void anExistingDatabaseGetsAnEmptyConfigurationTableAndKeepsItsData() {
        Map<String, Object> before = jdbc.queryForMap("select id, email, role from " + DEV + ".users where id = 'u1'");
        migrate();

        assertThat(jdbc.queryForList("select version from " + DEV + ".flyway_schema_history order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
        assertThat(jdbc.queryForMap("select id, email, role from " + DEV + ".users where id = 'u1'")).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from " + DEV + ".monetization_config", Integer.class)).isZero();
    }

    @Test
    void freshAndUpgradedDatabasesHaveTheSameConfigurationObjects() {
        migrate();
        for (String schema : List.of("public", DEV)) {
            assertThat(jdbc.queryForList("select constraint_name from information_schema.table_constraints where table_schema = ? "
                    + "and table_name = 'monetization_config'", String.class, schema)).as(schema)
                    .contains("pk_monetization_config", "ck_monetization_config_version", "ck_monetization_config_setting",
                            "fk_monetization_config_created_by");
            assertThat(jdbc.queryForList("select event_manipulation from information_schema.triggers where event_object_schema = ? "
                    + "and event_object_table = 'monetization_config' and trigger_name = 'monetization_config_no_change' order by 1",
                    String.class, schema)).as(schema).containsExactly("DELETE", "UPDATE");
        }
    }
}
