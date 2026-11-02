package com.agafari.com.trial;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.service.TrialService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.RequestBuilder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF E4-M5 (AC5, AC7a, AC9a; proposal §16.2, §16.3), real PostgreSQL, deterministic: connection A holds the lock the
 * application takes and an uncommitted change; the request waits on it; A commits. Convert vs job, convert vs
 * configuration change, expire-on-touch vs job (exactly one TRIAL_EXPIRED), the job vs a running convert.
 */
class TrialConcurrencyTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private TrialService trials;

    private User admin;
    private String bearer;
    private Tenant t;
    private String trialId;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        trials.setEnabled(true);
        admin = data.admin("trial-racer");
        bearer = data.adminBearer(admin);
        t = data.tenant("trial-race-cafe");
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        trialId = UUID.randomUUID().toString();
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        trials.setEnabled(false);
    }

    private void insertTrial(int daysAgoStarted) {
        Instant start = Instant.now().minus(Duration.ofDays(daysAgoStarted));
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, "
                + "created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_1', 'TRIAL', 1, ?, 'TRIAL', ?, 'system', ?, 'system', 0)",
                trialId, t.businessId(), Timestamp.from(start.plus(Duration.ofDays(30))), Timestamp.from(start), Timestamp.from(start));
    }

    private Future<String> async(RequestBuilder request) {
        return pool.submit(() -> {
            MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
            String body = response.getContentAsString();
            return response.getStatus() + " " + (body.contains("\"code\"") ? JsonPath.read(body, "$.code") : "");
        });
    }

    private RequestBuilder convert(int configVersion) {
        return post("/api/admin/businesses/{id}/trial/convert", t.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedConfigVersion\":" + configVersion + ",\"rowVersion\":0}");
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
            s.setString(1, t.businessId());
            s.executeQuery();
        }
    }

    private void expireLikeTheJob(Connection c) throws Exception {
        try (PreparedStatement s = c.prepareStatement("update business_subscription set status = 'EXPIRED', ended_at = period_end, "
                + "row_version = row_version + 1 where id = ? and status = 'TRIAL'")) {
            s.setString(1, trialId);
            assertThat(s.executeUpdate()).isEqualTo(1);
        }
        try (PreparedStatement s = c.prepareStatement("insert into admin_audit_event (id, occurred_at, actor_label, action, target_type, target_id, outcome, detail) "
                + "values (?, now(), 'system', 'TRIAL_EXPIRED', 'BUSINESS_SUBSCRIPTION', ?, 'SUCCESS', 'job')")) {
            s.setString(1, UUID.randomUUID().toString());
            s.setString(2, trialId);
            s.executeUpdate();
        }
    }

    private int expiredAudits() {
        return jdbc.queryForObject("select count(*) from admin_audit_event where action = 'TRIAL_EXPIRED'", Integer.class);
    }

    @Test
    void jobFirstAConvertWaitingOnTheRestaurantIsRefused() throws Exception {
        insertTrial(31);
        try (Connection a = dataSource.getConnection()) {
            a.setAutoCommit(false);
            lockRestaurant(a);
            expireLikeTheJob(a);
            Future<String> result = async(convert(1));
            waitFor("the restaurant lock", "query like 'select id from business where id%'");
            a.commit();
            assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo("409 SUBSCRIPTION_VERSION_CONFLICT");
        }
        assertThat(jdbc.queryForObject("select count(*) from business_subscription where source = 'MANUAL'", Integer.class)).isZero();
        assertThat(expiredAudits()).isEqualTo(1);
    }

    @Test
    void configurationChangeFirstAConvertIsRefusedAndTheTrialStays() throws Exception {
        insertTrial(10);
        try (Connection a = dataSource.getConnection()) {
            a.setAutoCommit(false);
            try (Statement s = a.createStatement()) {
                s.execute("select pg_advisory_xact_lock(" + MonetizationLocks.CONFIG_LOCK_KEY + ")");
            }
            try (PreparedStatement s = a.prepareStatement("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, "
                    + "effective_from, created_at, created_by_user_id) values (2, 'SETTING_2', true, false, false, now(), now(), ?)")) {
                s.setString(1, admin.getId());
                s.executeUpdate();
            }
            Future<String> result = async(convert(1));
            waitFor("the configuration lock", "wait_event = 'advisory'");
            a.commit();
            assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo("409 CONFIG_VERSION_CONFLICT");
        }
        assertThat(jdbc.queryForObject("select status from business_subscription where id = ?", String.class, trialId)).isEqualTo("TRIAL");
    }

    @Test
    void expireOnTouchWaitingForTheJobAuditsTheExpiryOnlyOnce() throws Exception {
        insertTrial(31);
        try (Connection a = dataSource.getConnection()) {
            a.setAutoCommit(false);
            lockRestaurant(a);
            expireLikeTheJob(a);
            Future<String> record = async(post("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", bearer)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"planCode\":\"MODEL_1\",\"expectedConfigVersion\":1}"));
            waitFor("the restaurant lock", "query like 'select id from business where id%'");
            a.commit();
            assertThat(record.get(30, TimeUnit.SECONDS)).isEqualTo("201 ");
        }
        assertThat(expiredAudits()).isEqualTo(1);
    }

    @Test
    void convertFirstTheJobWaitsAndThenFindsNothingToExpire() throws Exception {
        insertTrial(31);
        try (Connection a = dataSource.getConnection()) {
            a.setAutoCommit(false);
            lockRestaurant(a);
            try (PreparedStatement s = a.prepareStatement("update business_subscription set status = 'EXPIRED', ended_at = period_end, "
                    + "row_version = row_version + 1 where id = ?")) {
                s.setString(1, trialId);
                s.executeUpdate(); // a convert in progress has ended the trial
            }
            Future<Integer> job = pool.submit(() -> trials.runExpiry());
            waitFor("the restaurant lock", "query like 'select id from business where id%'");
            a.commit();
            assertThat(job.get(30, TimeUnit.SECONDS)).isZero();
        }
        assertThat(expiredAudits()).isZero();
    }
}
