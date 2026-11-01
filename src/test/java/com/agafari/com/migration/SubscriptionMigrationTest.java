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
 * AGF E4-M3 (AC1, AC12): V13 adds the read-only, seeded subscription_plan table and the business_subscription table
 * (one current row per restaurant, frozen history, guard trigger). No restaurant gets a subscription. Fresh
 * and existing databases get the same objects; existing data is untouched.
 */
class SubscriptionMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "subscription_dev";

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
    void anExistingDatabaseGetsThePlansAndNoSubscriptionsAndKeepsItsData() {
        Map<String, Object> before = jdbc.queryForMap("select id, email, role from " + DEV + ".users where id = 'u1'");
        migrate();

        assertThat(jdbc.queryForList("select version from " + DEV + ".flyway_schema_history order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14");
        assertThat(jdbc.queryForMap("select id, email, role from " + DEV + ".users where id = 'u1'")).isEqualTo(before);
        assertThat(jdbc.queryForList("select code from " + DEV + ".subscription_plan order by code", String.class))
                .containsExactly("MODEL_1", "MODEL_2", "MODEL_3", "MODEL_4");
        assertThat(jdbc.queryForObject("select count(*) from " + DEV + ".business_subscription", Integer.class)).isZero();
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_schema = ? and table_name = 'subscription_plan'",
                String.class, DEV)).containsExactlyInAnyOrder("code", "model", "setting", "ad_source", "paid"); // no price, trial or limit columns
    }

    @Test
    void freshAndUpgradedDatabasesHaveTheSameSubscriptionObjects() {
        migrate();
        for (String schema : List.of("public", DEV)) {
            assertThat(jdbc.queryForList("select constraint_name from information_schema.table_constraints where table_schema = ? "
                    + "and table_name = 'business_subscription'", String.class, schema)).as(schema)
                    .contains("pk_business_subscription", "fk_business_subscription_business", "fk_business_subscription_plan",
                            "fk_business_subscription_config", "ck_business_subscription_status", "ck_business_subscription_source",
                            "ck_business_subscription_period", "ck_business_subscription_ended", "ck_business_subscription_trial");
            assertThat(jdbc.queryForObject("select indexdef from pg_indexes where schemaname = ? and indexname = 'uk_business_subscription_current'",
                    String.class, schema)).as(schema).contains("UNIQUE").contains("(business_id)").contains("WHERE");
            assertThat(jdbc.queryForList("select event_manipulation from information_schema.triggers where event_object_schema = ? "
                    + "and event_object_table = 'business_subscription' and trigger_name = 'business_subscription_guard' order by 1",
                    String.class, schema)).as(schema).containsExactly("DELETE", "UPDATE");
            assertThat(jdbc.queryForList("select event_manipulation from information_schema.triggers where event_object_schema = ? "
                    + "and event_object_table = 'subscription_plan' and trigger_name = 'subscription_plan_no_change' order by 1",
                    String.class, schema)).as(schema).containsExactly("DELETE", "UPDATE");
        }
    }

    @Test
    void theSeededPlansMatchTheApprovedPlansInCode() {
        assertThat(jdbc.queryForList("select code || ':' || setting || ':' || ad_source || ':' || paid from subscription_plan order by code", String.class))
                .containsExactlyElementsOf(com.agafari.com.jpa.entity.SubscriptionPlan.APPROVED.stream()
                        .map(p -> p.getCode() + ":" + p.getSetting() + ":" + p.getAdSource() + ":" + p.isPaid()).toList());
    }
}
