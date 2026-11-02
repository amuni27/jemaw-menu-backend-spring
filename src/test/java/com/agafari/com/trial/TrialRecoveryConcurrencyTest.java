package com.agafari.com.trial;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.service.TrialService;
import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.trial.TrialTestSupport.businessOf;
import static com.agafari.com.trial.TrialTestSupport.register;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M5 recovery (AC-R6), real PostgreSQL, deterministic: connection A holds the lock the application takes and an
 * uncommitted change; the job's retry waits on it; A commits. Fulfilment in progress elsewhere, a trial created
 * elsewhere, a configuration change, and a second job instance: always exactly one consistent outcome, never two trials.
 */
class TrialRecoveryConcurrencyTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private TrialService trials;

    private User admin;
    private String businessId;
    private ExecutorService pool;

    @BeforeEach
    void setUp() throws Exception {
        trials.setEnabled(true);
        admin = data.admin("recovery-racer");
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        trials.setAfterCommitEnabled(false); // a registration whose after-commit step never ran
        try {
            register(mockMvc, "recovery-race").andExpect(status().isCreated());
        } finally {
            trials.setAfterCommitEnabled(true);
        }
        businessId = businessOf(jdbc, "recovery-race");
        pool = Executors.newFixedThreadPool(1);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        trials.setEnabled(false);
    }

    private void waitFor(String what, String condition) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer n = jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and " + condition, Integer.class);
            if (n != null && n > 0) return;
            Thread.sleep(50);
        }
        fail("never waited on " + what);
    }

    private void lockRestaurant(Connection c) throws Exception {
        try (PreparedStatement s = c.prepareStatement("select id from business where id = ? for no key update")) {
            s.setString(1, businessId);
            s.executeQuery();
        }
    }

    private String insertTrial(Connection c) throws Exception {
        String id = UUID.randomUUID().toString();
        try (PreparedStatement s = c.prepareStatement("insert into business_subscription (id, business_id, plan_code, status, config_version, "
                + "period_end, source, created_at, created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_1', 'TRIAL', 1, "
                + "now() + interval '30 days', 'TRIAL', now(), 'system', now(), 'system', 0)")) {
            s.setString(1, id);
            s.setString(2, businessId);
            s.executeUpdate();
        }
        return id;
    }

    private String intentStatus() {
        return jdbc.queryForObject("select status || '/' || coalesce(resolution, '-') from trial_intent where business_id = ?", String.class, businessId);
    }

    private int trials() {
        return jdbc.queryForObject("select count(*) from business_subscription where business_id = ?", Integer.class, businessId);
    }

    @Test
    void anAfterCommitStartInProgressMakesTheWaitingRetryDoNothing() throws Exception {
        try (Connection a = dataSource.getConnection()) {
            a.setAutoCommit(false);
            lockRestaurant(a);
            String trialId = insertTrial(a);
            try (PreparedStatement s = a.prepareStatement("update trial_intent set status = 'STARTED', resolution = 'AFTER_COMMIT', subscription_id = ?, "
                    + "resolved_at = now(), row_version = row_version + 1 where business_id = ?")) {
                s.setString(1, trialId);
                s.setString(2, businessId);
                s.executeUpdate();
            }
            Future<Integer> job = pool.submit(() -> trials.runRecovery());
            waitFor("the restaurant lock", "query like 'select id from business where id%'");
            a.commit();
            assertThat(job.get(30, TimeUnit.SECONDS)).isZero();
        }
        assertThat(intentStatus()).isEqualTo("STARTED/AFTER_COMMIT");
        assertThat(trials()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action like 'TRIAL%'", Integer.class)).isZero(); // nothing, not a failure
    }

    @Test
    void aTrialCreatedElsewhereFirstResolvesTheIntentWithoutASecondTrial() throws Exception {
        try (Connection a = dataSource.getConnection()) {
            a.setAutoCommit(false);
            lockRestaurant(a);
            insertTrial(a); // e.g. a trial written without touching the intent
            Future<Integer> job = pool.submit(() -> trials.runRecovery());
            waitFor("the restaurant lock", "query like 'select id from business where id%'");
            a.commit();
            assertThat(job.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(intentStatus()).isEqualTo("STARTED/EXISTING_TRIAL");
        assertThat(trials()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = 'TRIAL_START_FAILED'", Integer.class)).isZero();
    }

    @Test
    void aConfigurationChangeToSetting2FirstKeepsTheIntentPendingWithoutATrial() throws Exception {
        try (Connection a = dataSource.getConnection()) {
            a.setAutoCommit(false);
            try (Statement s = a.createStatement()) {
                s.execute("select pg_advisory_xact_lock(" + MonetizationLocks.CONFIG_LOCK_KEY + ")");
            }
            try (PreparedStatement s = a.prepareStatement("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, "
                    + "model_4_enabled, effective_from, created_at, created_by_user_id) values (2, 'SETTING_2', true, false, false, now(), now(), ?)")) {
                s.setString(1, admin.getId());
                s.executeUpdate();
            }
            Future<Integer> job = pool.submit(() -> trials.runRecovery());
            waitFor("the configuration lock", "wait_event = 'advisory'");
            a.commit();
            assertThat(job.get(30, TimeUnit.SECONDS)).isZero();
        }
        assertThat(intentStatus()).isEqualTo("PENDING/-");
        assertThat(trials()).isZero();
    }

    @Test
    void aSecondJobInstanceSkipsRecoveryWhileOneRuns() throws Exception {
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (Statement s = other.createStatement()) {
                s.execute("select pg_advisory_xact_lock(" + TrialService.TRIAL_JOB_LOCK_KEY + ")");
            }
            assertThat(trials.runRecovery()).isEqualTo(-1);
            other.rollback();
        }
        assertThat(intentStatus()).isEqualTo("PENDING/-");
        assertThat(trials.runRecovery()).isEqualTo(1);
        assertThat(trials()).isEqualTo(1);
    }
}
