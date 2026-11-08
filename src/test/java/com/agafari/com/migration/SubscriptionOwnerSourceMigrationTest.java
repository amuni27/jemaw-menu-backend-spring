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
import java.util.UUID;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF Self-Service Phase A (SS-4, SS-AC8): V19 only widens the subscription source check to {@code OWNER}. Fresh and
 * upgraded databases reach V19; existing rows are untouched; an owner-sourced row is never a trial; other sources stay
 * refused; the guard trigger still keeps the source immutable.
 */
class SubscriptionOwnerSourceMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "owner_source_dev";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void existingDatabase() throws Exception {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
        jdbc.execute("create schema " + DEV);
        String ddl = new ClassPathResource("db/legacy/ddl-auto-schema.sql").getContentAsString(StandardCharsets.UTF_8);
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("set search_path to " + DEV);
            for (String statement : ddl.split(";")) {
                String sql = statement.lines().filter(l -> !l.strip().startsWith("--")).reduce("", (a, b) -> a + "\n" + b).strip();
                if (!sql.isEmpty()) c.createStatement().execute(sql);
            }
            c.createStatement().execute("set search_path to public");
        }
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
    }

    @Test
    void freshAndUpgradedDatabasesReachV19() {
        Flyway.configure().dataSource(dataSource).schemas(DEV).locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("1").load().migrate();
        assertThat(jdbc.queryForList("select version from " + DEV + ".flyway_schema_history order by installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21");
        assertThat(jdbc.queryForObject("select pg_get_constraintdef(oid) from pg_constraint where conname = 'ck_business_subscription_source' "
                + "and connamespace = (select oid from pg_namespace where nspname = ?)", String.class, DEV)).contains("OWNER");
    }

    private String insert(String businessId, String status, String source, String plan, String periodEnd) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, created_by_user_id, "
                + "updated_at, updated_by_user_id, row_version) values (?, ?, ?, ?, 1, " + periodEnd + ", ?, now(), 'o', now(), 'o', 0)", id, businessId, plan, status, source);
        return id;
    }

    @Test
    void theDatabaseAcceptsOwnerSourcedActiveRowsOnly() {
        var admin = data.admin("ss-mig-admin");
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        Tenant a = data.tenant("ss-mig-a"), b = data.tenant("ss-mig-b"), c = data.tenant("ss-mig-c");
        String id = insert(a.businessId(), "ACTIVE", "OWNER", "MODEL_2", "null");
        assertThatThrownBy(() -> insert(b.businessId(), "ACTIVE", "SELF", "MODEL_2", "null")).hasMessageContaining("ck_business_subscription_source");
        assertThatThrownBy(() -> insert(c.businessId(), "TRIAL", "OWNER", "MODEL_1", "now() + interval '30 days'")).hasMessageContaining("ck_business_subscription_trial_source");
        assertThatThrownBy(() -> jdbc.update("update business_subscription set source = 'MANUAL' where id = ?", id)).hasMessageContaining("can't be changed");
        assertThatThrownBy(() -> insert(a.businessId(), "ACTIVE", "OWNER", "MODEL_2", "null")).hasMessageContaining("uk_business_subscription_current");
    }
}
