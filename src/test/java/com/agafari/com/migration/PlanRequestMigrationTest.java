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
 * AGF E4-M4 (AC13): V14 adds the plan_request table
 * (one pending request per restaurant, frozen history, guard trigger with the I1 backstop). It starts empty. Fresh
 * and existing databases get the same objects; existing data is untouched.
 */
class PlanRequestMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "planrequest_dev";

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
    void anExistingDatabaseGetsAnEmptyPlanRequestTableAndKeepsItsData() {
        Map<String, Object> before = jdbc.queryForMap("select id, email, role from " + DEV + ".users where id = 'u1'");
        migrate();

        assertThat(jdbc.queryForList("select version from " + DEV + ".flyway_schema_history order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14");
        assertThat(jdbc.queryForMap("select id, email, role from " + DEV + ".users where id = 'u1'")).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from " + DEV + ".plan_request", Integer.class)).isZero();
    }

    @Test
    void freshAndUpgradedDatabasesHaveTheSamePlanRequestObjects() {
        migrate();
        for (String schema : List.of("public", DEV)) {
            assertThat(jdbc.queryForList("select constraint_name from information_schema.table_constraints where table_schema = ? "
                    + "and table_name = 'plan_request'", String.class, schema)).as(schema)
                    .contains("pk_plan_request", "fk_plan_request_business", "fk_plan_request_plan", "fk_plan_request_config",
                            "fk_plan_request_subscription", "ck_plan_request_status", "ck_plan_request_resolved",
                            "ck_plan_request_subscription", "ck_plan_request_model");
            assertThat(jdbc.queryForObject("select indexdef from pg_indexes where schemaname = ? and indexname = 'uk_plan_request_pending'",
                    String.class, schema)).as(schema).contains("UNIQUE").contains("(business_id)").contains("WHERE");
            assertThat(jdbc.queryForList("select event_manipulation from information_schema.triggers where event_object_schema = ? "
                    + "and event_object_table = 'plan_request' and trigger_name = 'plan_request_guard' order by 1",
                    String.class, schema)).as(schema).containsExactly("DELETE", "INSERT", "UPDATE");
        }
    }
}
