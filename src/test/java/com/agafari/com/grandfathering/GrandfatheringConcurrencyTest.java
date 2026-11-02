package com.agafari.com.grandfathering;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.GrandfatheringService;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.service.PlanRequestService;
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

import static com.agafari.com.grandfathering.GrandfatheringFixtures.manual;
import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * AGF E4-M6 (AC8, AC10, §6), real PostgreSQL, deterministic: connection A holds the lock the application takes and an
 * uncommitted change; the job, a transition-date write or an owner request waits on it; A commits. The job re-checks under
 * the configuration and restaurant locks, so a re-enabled plan or an extension committed first is respected, and an end
 * by another path is audited only once.
 */
class GrandfatheringConcurrencyTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private GrandfatheringService grandfathering;
    @Autowired private PlanRequestService planRequests;

    private User admin;
    private Tenant t;
    private String subscriptionId;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        admin = data.admin("gf-racer");
        t = data.tenant("gf-race");
        jdbc.update("update business set time_zone = 'UTC' where id = ?", t.businessId());
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        pool = Executors.newFixedThreadPool(1);
        grandfathering.setEnabled(true);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        grandfathering.setEnabled(false);
        planRequests.setOwnerPlansVisible(false);
    }

    private void overdueThenDisable() {
        subscriptionId = manual(jdbc, t.businessId(), "MODEL_3", 1, Instant.now().minus(Duration.ofDays(2)), Instant.now().minus(Duration.ofDays(60)), admin.getId());
        config(jdbc, 2, "SETTING_2", true, false, true, admin.getId());
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

    private void insertConfig(Connection c, int version, boolean model3) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("select pg_advisory_xact_lock(" + MonetizationLocks.CONFIG_LOCK_KEY + ")");
        }
        try (PreparedStatement s = c.prepareStatement("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, "
                + "effective_from, created_at, created_by_user_id) values (?, 'SETTING_2', true, ?, true, now(), now(), ?)")) {
            s.setInt(1, version);
            s.setBoolean(2, model3);
            s.setString(3, admin.getId());
            s.executeUpdate();
        }
    }

    private Future<String> async(RequestBuilder request) {
        return pool.submit(() -> {
            MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
            String body = response.getContentAsString();
            return response.getStatus() + " " + (body.contains("\"code\"") ? JsonPath.read(body, "$.code") : "");
        });
    }

    private String status() {
        return jdbc.queryForObject("select status from business_subscription where id = ?", String.class, subscriptionId);
    }

    private int ended() {
        return jdbc.queryForObject("select count(*) from admin_audit_event where action = 'GRANDFATHERING_ENDED'", Integer.class);
    }

    @Test
    void aConfigurationChangeReenablingThePlanFirstMeansTheWaitingJobEndsNothing() throws Exception {
        overdueThenDisable();
        try (Connection a = dataSource.getConnection()) {
            a.setAutoCommit(false);
            insertConfig(a, 3, true);
            Future<Integer> job = pool.submit(() -> grandfathering.runEnd());
            waitFor("the configuration lock", "wait_event = 'advisory'");
            a.commit();
            assertThat(job.get(30, TimeUnit.SECONDS)).isZero();
        }
        assertThat(status()).isEqualTo("ACTIVE");
        assertThat(ended()).isZero();
    }

    @Test
    void anExtensionCommittedFirstMeansTheWaitingJobEndsNothing() throws Exception {
        overdueThenDisable();
        try (Connection a = dataSource.getConnection()) {
            a.setAutoCommit(false);
            lockRestaurant(a);
            try (PreparedStatement s = a.prepareStatement("update business_subscription set period_end = ?, row_version = row_version + 1 where id = ?")) {
                s.setTimestamp(1, Timestamp.from(Instant.now().plus(Duration.ofDays(30))));
                s.setString(2, subscriptionId);
                s.executeUpdate();
            }
            Future<Integer> job = pool.submit(() -> grandfathering.runEnd());
            waitFor("the restaurant lock", "query like 'select id from business where id%'");
            a.commit();
            assertThat(job.get(30, TimeUnit.SECONDS)).isZero();
        }
        assertThat(status()).isEqualTo("ACTIVE");
    }

    @Test
    void anEndByAnotherPathFirstIsAuditedOnlyOnceAndTheWaitingOwnerRequestSucceeds() throws Exception {
        planRequests.setOwnerPlansVisible(true);
        overdueThenDisable();
        try (Connection a = dataSource.getConnection()) {
            a.setAutoCommit(false);
            lockRestaurant(a);
            try (PreparedStatement s = a.prepareStatement("update business_subscription set status = 'EXPIRED', ended_at = period_end, "
                    + "row_version = row_version + 1 where id = ?")) {
                s.setString(1, subscriptionId);
                s.executeUpdate();
            }
            try (PreparedStatement s = a.prepareStatement("insert into admin_audit_event (id, occurred_at, actor_label, action, target_type, target_id, outcome, detail) "
                    + "values (?, now(), 'system', 'GRANDFATHERING_ENDED', 'BUSINESS_SUBSCRIPTION', ?, 'SUCCESS', 'job')")) {
                s.setString(1, UUID.randomUUID().toString());
                s.setString(2, subscriptionId);
                s.executeUpdate();
            }
            Future<String> request = async(post("/api/plan/requests").header("Authorization", data.bearer(t)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"planCode\":\"MODEL_4\",\"expectedConfigVersion\":2}"));
            waitFor("the restaurant lock", "query like 'select id from business where id%'");
            a.commit();
            assertThat(request.get(30, TimeUnit.SECONDS)).isEqualTo("201 ");
        }
        assertThat(ended()).isEqualTo(1);
    }

    @Test
    void aTransitionDateWriteWaitingOnAConfigurationChangeThatReenablesThePlanIsRefused() throws Exception {
        subscriptionId = manual(jdbc, t.businessId(), "MODEL_3", 1, null, Instant.now().minus(Duration.ofDays(60)), admin.getId());
        config(jdbc, 2, "SETTING_2", true, false, true, admin.getId());
        try (Connection a = dataSource.getConnection()) {
            a.setAutoCommit(false);
            insertConfig(a, 3, true);
            Future<String> set = async(put("/api/admin/businesses/{id}/subscription/transition-date", t.businessId())
                    .header("Authorization", data.adminBearer(admin)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"transitionDate\":\"2027-06-30\",\"rowVersion\":0}"));
            waitFor("the configuration lock", "wait_event = 'advisory'");
            a.commit();
            assertThat(set.get(30, TimeUnit.SECONDS)).isEqualTo("400 ");
        }
        assertThat(jdbc.queryForObject("select transition_date is null from business_subscription where id = ?", Boolean.class, subscriptionId)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = 'SUBSCRIPTION_TRANSITION_SET'", Integer.class)).isZero();
    }

    @Test
    void aTransitionDateWrittenFirstIsRecordedUnderTheVersionCurrentAtItsCommit() throws Exception {
        subscriptionId = manual(jdbc, t.businessId(), "MODEL_3", 1, null, Instant.now().minus(Duration.ofDays(60)), admin.getId());
        config(jdbc, 2, "SETTING_2", true, false, true, admin.getId());
        try (Connection a = dataSource.getConnection()) {
            a.setAutoCommit(false);
            lockRestaurant(a); // the set waits on the restaurant after taking the shared configuration lock
            Future<String> set = async(put("/api/admin/businesses/{id}/subscription/transition-date", t.businessId())
                    .header("Authorization", data.adminBearer(admin)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"transitionDate\":\"2027-06-30\",\"rowVersion\":0}"));
            waitFor("the restaurant lock", "query like 'select id from business where id%'");
            a.commit();
            assertThat(set.get(30, TimeUnit.SECONDS)).isEqualTo("200 ");
        }
        assertThat(jdbc.queryForObject("select detail from admin_audit_event where action = 'SUBSCRIPTION_TRANSITION_SET'", String.class))
                .isEqualTo("config=2;transition=2027-07-01T00:00:00Z");
    }
}
