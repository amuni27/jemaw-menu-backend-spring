package com.agafari.com.migration;

import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
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

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF E4-M5 recovery (AC-R10): V16 adds trial_intent. Existing data is untouched; fresh and upgraded databases get the same
 * objects. One intent per restaurant; new intents are pending; resolved intents are frozen; no deletes; attempts only grow.
 */
class TrialIntentMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "trial_intent_dev";

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
    void anExistingDatabaseGetsTheIntentTableAndKeepsItsData() {
        Map<String, Object> before = jdbc.queryForMap("select id, email, role from " + DEV + ".users where id = 'u1'");
        migrate();
        assertThat(jdbc.queryForList("select version from " + DEV + ".flyway_schema_history order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23.1", "23.2", "23.3", "23.4", "23.5");
        assertThat(jdbc.queryForMap("select id, email, role from " + DEV + ".users where id = 'u1'")).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from " + DEV + ".trial_intent", Integer.class)).isZero();
    }

    @Test
    void freshAndUpgradedDatabasesHaveTheSameIntentObjects() {
        migrate();
        for (String schema : List.of("public", DEV)) {
            assertThat(jdbc.queryForList("select constraint_name from information_schema.table_constraints where table_schema = ? "
                    + "and table_name = 'trial_intent'", String.class, schema)).as(schema)
                    .contains("pk_trial_intent", "fk_trial_intent_business", "fk_trial_intent_config", "fk_trial_intent_subscription",
                            "ck_trial_intent_status", "ck_trial_intent_resolved", "ck_trial_intent_resolution", "ck_trial_intent_attempts");
            assertThat(jdbc.queryForObject("select indexdef from pg_indexes where schemaname = ? and indexname = 'ix_trial_intent_retry'",
                    String.class, schema)).as(schema).contains("last_attempt_at NULLS FIRST").contains("PENDING");
            assertThat(jdbc.queryForObject("select count(*) from information_schema.triggers where trigger_schema = ? and trigger_name = 'trial_intent_guard'",
                    Integer.class, schema)).as(schema).isPositive();
        }
    }

    @Test
    void theDatabaseEnforcesTheIntentLifecycle() {
        String adminId = data.admin("intent-rules").getId();
        config(jdbc, 1, "SETTING_1", false, false, false, adminId);
        Tenant t = data.tenant("intent-rules");
        String insert = "insert into trial_intent (business_id, config_version, status, failed_attempts, created_at, row_version) values (?, 1, ?, ?, now(), 0)";

        assertThatThrownBy(() -> jdbc.update(insert, t.businessId(), "PENDING", 1)).hasMessageContaining("must be pending with no attempts");
        jdbc.update(insert, t.businessId(), "PENDING", 0);
        assertThatThrownBy(() -> jdbc.update(insert, t.businessId(), "PENDING", 0)).hasMessageContaining("pk_trial_intent");
        assertThatThrownBy(() -> jdbc.update("update trial_intent set failed_attempts = -1 where business_id = ?", t.businessId()))
                .hasMessageContaining("attempts only grow");
        assertThatThrownBy(() -> jdbc.update("update trial_intent set config_version = 2 where business_id = ?", t.businessId()))
                .hasMessageContaining("can't be changed");
        assertThatThrownBy(() -> jdbc.update("update trial_intent set status = 'STARTED', resolution = 'RETRY', resolved_at = now() where business_id = ?",
                t.businessId())).hasMessageContaining("ck_trial_intent_resolved");
        String subscriptionId = subscription(jdbc, t.businessId(), "MODEL_1", 1, adminId);
        assertThatThrownBy(() -> jdbc.update("update trial_intent set status = 'NOT_STARTED', resolution = 'RETRY', subscription_id = ?, resolved_at = now() "
                + "where business_id = ?", subscriptionId, t.businessId())).hasMessageContaining("ck_trial_intent_resolution");
        jdbc.update("update trial_intent set status = 'NOT_STARTED', resolution = 'SUBSCRIPTION_EXISTS', subscription_id = ?, resolved_at = now() "
                + "where business_id = ?", subscriptionId, t.businessId());
        assertThatThrownBy(() -> jdbc.update("update trial_intent set last_error = 'x' where business_id = ?", t.businessId()))
                .hasMessageContaining("resolved trial intents can't be changed");
        assertThatThrownBy(() -> jdbc.update("delete from trial_intent where business_id = ?", t.businessId())).hasMessageContaining("can't be deleted");
    }
}
