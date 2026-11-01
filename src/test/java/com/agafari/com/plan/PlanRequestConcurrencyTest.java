package com.agafari.com.plan;

import com.agafari.com.jpa.entity.User;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.pendingRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * AGF E4-M4 (AC6, AC6a, AC8, AC8a; proposal §16), real PostgreSQL. Deterministic races: connection A holds the same lock the
 * application takes and an uncommitted row; the test waits until the API request is blocked on that lock, then commits A.
 * §16.1 owner request vs Super Admin record (restaurant lock, invariant I1); §16.2 configuration change vs fulfil, owner
 * request and record (configuration advisory lock, invariant I2); one pending request per restaurant; fulfil vs withdraw.
 */
class PlanRequestConcurrencyTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlanRequestService planRequests;

    private User admin;
    private String adminBearer;
    private Tenant owner;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        planRequests.setOwnerPlansVisible(true);
        admin = data.admin("plan-racer");
        adminBearer = data.adminBearer(admin);
        owner = data.tenant("racing-cafe");
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        pool = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        planRequests.setOwnerPlansVisible(false);
    }

    // ------------------------------------------------------------------------------------------------ requests

    private RequestBuilder ownerRequest(String plan, int version) {
        return post("/api/plan/requests").header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"planCode\":\"" + plan + "\",\"expectedConfigVersion\":" + version + "}");
    }

    private RequestBuilder adminRecord(String plan, int version) {
        return post("/api/admin/businesses/{id}/subscription", owner.businessId()).header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"planCode\":\"" + plan + "\",\"expectedConfigVersion\":" + version + "}");
    }

    private RequestBuilder fulfil(String id, int version, int rowVersion) {
        return post("/api/admin/plan-requests/{id}/fulfil", id).header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedConfigVersion\":" + version + ",\"rowVersion\":" + rowVersion + "}");
    }

    private Future<String> async(RequestBuilder request) {
        return pool.submit(() -> {
            MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
            String body = response.getContentAsString();
            String code = body.contains("\"code\"") ? JsonPath.read(body, "$.code") : "";
            return response.getStatus() + " " + code;
        });
    }

    private void waitFor(String what, String sqlCondition) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer waiting = jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and " + sqlCondition, Integer.class);
            if (waiting != null && waiting > 0) return;
            Thread.sleep(50);
        }
        fail("the request never waited on " + what);
    }

    private void waitForRestaurantLock() throws InterruptedException {
        waitFor("the restaurant lock", "query like 'select id from business where id%'");
    }

    private void waitForConfigLock() throws InterruptedException {
        waitFor("the configuration lock", "wait_event = 'advisory'");
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table + " where business_id = ?", Integer.class, owner.businessId());
    }

    private int pending() {
        return jdbc.queryForObject("select count(*) from plan_request where business_id = ? and status = 'PENDING'", Integer.class, owner.businessId());
    }

    private void lockRestaurant(Connection c) throws Exception {
        try (PreparedStatement s = c.prepareStatement("select id from business where id = ? for no key update")) {
            s.setString(1, owner.businessId());
            s.executeQuery();
        }
    }

    private void lockConfig(Connection c, boolean exclusive) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute("select " + (exclusive ? "pg_advisory_xact_lock(" : "pg_advisory_xact_lock_shared(") + MonetizationLocks.CONFIG_LOCK_KEY + ")");
        }
    }

    private void insertSubscription(Connection c, String plan, int version) throws Exception {
        try (PreparedStatement s = c.prepareStatement("insert into business_subscription (id, business_id, plan_code, status, config_version, source, "
                + "created_at, created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, ?, 'ACTIVE', ?, 'MANUAL', now(), ?, now(), ?, 0)")) {
            s.setString(1, UUID.randomUUID().toString());
            s.setString(2, owner.businessId());
            s.setString(3, plan);
            s.setInt(4, version);
            s.setString(5, admin.getId());
            s.setString(6, admin.getId());
            s.executeUpdate();
        }
    }

    // -------------------------------------------------------------------- §16.1 owner request vs admin record

    @Test
    void s161AdminFirstTheOwnersRequestIsRefusedAndNoPendingRequestAppears() throws Exception {
        try (Connection a = dataSource.getConnection()) {
            a.setAutoCommit(false);
            lockRestaurant(a);
            insertSubscription(a, "MODEL_4", 1);
            Future<String> ownerResult = async(ownerRequest("MODEL_2", 1));
            waitForRestaurantLock();
            a.commit();
            assertThat(ownerResult.get(30, TimeUnit.SECONDS)).isEqualTo("409 SUBSCRIPTION_EXISTS");
        }
        assertThat(count("business_subscription")).isEqualTo(1);
        assertThat(count("plan_request")).isZero();
    }

    @Test
    void s161OwnerFirstTheAdminsDirectRecordIsRefusedAndNoSubscriptionAppears() throws Exception {
        try (Connection a = dataSource.getConnection()) {
            a.setAutoCommit(false);
            lockRestaurant(a);
            try (PreparedStatement s = a.prepareStatement("insert into plan_request (id, business_id, plan_code, config_version, status, requested_at, "
                    + "requested_by_user_id, row_version) values ('owner-first', ?, 'MODEL_2', 1, 'PENDING', now(), ?, 0)")) {
                s.setString(1, owner.businessId());
                s.setString(2, owner.owner().getId());
                s.executeUpdate();
            }
            Future<String> adminResult = async(adminRecord("MODEL_4", 1));
            waitForRestaurantLock();
            a.commit();
            assertThat(adminResult.get(30, TimeUnit.SECONDS)).isEqualTo("409 PLAN_REQUEST_PENDING");
        }
        assertThat(count("business_subscription")).isZero();
        assertThat(pending()).isEqualTo(1);
    }

    @Test
    void s161OwnerAndAdminReleasedTogetherNeverLeaveBothASubscriptionAndAPendingRequest() throws Exception {
        for (int round = 0; round < 6; round++) {
            Tenant t = data.tenant("both-" + round);
            owner = t;
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Integer> o = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return mockMvc.perform(ownerRequest("MODEL_2", 1)).andReturn().getResponse().getStatus(); });
            Future<Integer> a = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return mockMvc.perform(adminRecord("MODEL_4", 1)).andReturn().getResponse().getStatus(); });
            List<Integer> statuses = List.of(o.get(30, TimeUnit.SECONDS), a.get(30, TimeUnit.SECONDS));
            assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(201, 409);
            assertThat(count("business_subscription") + pending()).as("round " + round).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------------------ one pending request; withdraw

    @Test
    void ac6ASecondPendingRequestThatCollidesOnTheIndexIsAConflictNotAnError() throws Exception {
        try (Connection a = dataSource.getConnection()) {
            a.setAutoCommit(false);
            try (PreparedStatement s = a.prepareStatement("insert into plan_request (id, business_id, plan_code, config_version, status, requested_at, "
                    + "requested_by_user_id, row_version) values ('first', ?, 'MODEL_4', 1, 'PENDING', now(), ?, 0)")) {
                s.setString(1, owner.businessId());
                s.setString(2, owner.owner().getId());
                s.executeUpdate(); // no restaurant lock: the request reaches the unique index and waits there
            }
            Future<String> second = async(ownerRequest("MODEL_2", 1));
            waitFor("the pending-request index", "query like 'insert into plan_request%'");
            a.commit();
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo("409 PLAN_REQUEST_EXISTS");
        }
        assertThat(jdbc.queryForList("select plan_code from plan_request where business_id = ?", String.class, owner.businessId())).containsExactly("MODEL_4");
    }

    @Test
    void ac8FulfilAfterAConcurrentWithdrawIsRefused() throws Exception {
        String id = pendingRequest(jdbc, owner.businessId(), "MODEL_4", 1, owner.owner().getId());
        try (Connection a = dataSource.getConnection()) {
            a.setAutoCommit(false);
            lockRestaurant(a);
            try (PreparedStatement s = a.prepareStatement("update plan_request set status = 'WITHDRAWN', resolved_at = now(), resolved_by_user_id = ?, "
                    + "row_version = row_version + 1 where id = ?")) {
                s.setString(1, owner.owner().getId());
                s.setString(2, id);
                s.executeUpdate();
            }
            Future<String> result = async(fulfil(id, 1, 0));
            waitForRestaurantLock();
            a.commit();
            assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo("409 PLAN_REQUEST_VERSION_CONFLICT");
        }
        assertThat(count("business_subscription")).isZero();
    }

    // ------------------------------------------------------------- §16.2 configuration change vs fulfil/request/record

    private void changeFirst(RequestBuilder request, String expected) throws Exception {
        try (Connection a = dataSource.getConnection()) {
            a.setAutoCommit(false);
            lockConfig(a, true);
            try (PreparedStatement s = a.prepareStatement("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, "
                    + "model_4_enabled, effective_from, created_at, created_by_user_id) values (2, 'SETTING_2', true, false, false, now(), now(), ?)")) {
                s.setString(1, admin.getId());
                s.executeUpdate(); // version 2 disables Model 4, not committed yet
            }
            Future<String> result = async(request);
            waitForConfigLock();
            a.commit();
            assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(expected);
        }
    }

    @Test
    void s162ChangeFirstAStaleFulfilIsRefusedAndTheRequestStaysPending() throws Exception {
        String id = pendingRequest(jdbc, owner.businessId(), "MODEL_4", 1, owner.owner().getId());
        changeFirst(fulfil(id, 1, 0), "409 CONFIG_VERSION_CONFLICT");
        assertThat(count("business_subscription")).isZero();
        assertThat(pending()).isEqualTo(1);

        assertThat(async(fulfil(id, 2, 0)).get(30, TimeUnit.SECONDS)).isEqualTo("409 PLAN_NOT_ENABLED"); // reloaded page: Model 4 is disabled
        assertThat(count("business_subscription")).isZero();
        assertThat(pending()).isEqualTo(1);
    }

    @Test
    void s162ChangeFirstAnOwnerRequestIsRefused() throws Exception {
        changeFirst(ownerRequest("MODEL_4", 1), "409 CONFIG_VERSION_CONFLICT");
        assertThat(count("plan_request")).isZero();
    }

    @Test
    void s162ChangeFirstADirectRecordIsRefused() throws Exception {
        changeFirst(adminRecord("MODEL_4", 1), "409 CONFIG_VERSION_CONFLICT");
        assertThat(count("business_subscription")).isZero();
    }

    @Test
    void s162FulfilFirstTheConfigurationChangeWaitsAndTheSubscriptionKeepsItsVersion() throws Exception {
        String id = pendingRequest(jdbc, owner.businessId(), "MODEL_4", 1, owner.owner().getId());
        try (Connection a = dataSource.getConnection()) {
            a.setAutoCommit(false);
            lockConfig(a, false);
            lockRestaurant(a);
            String subscriptionId = UUID.randomUUID().toString();
            try (PreparedStatement s = a.prepareStatement("insert into business_subscription (id, business_id, plan_code, status, config_version, source, "
                    + "created_at, created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_4', 'ACTIVE', 1, 'MANUAL', now(), ?, now(), ?, 0)")) {
                s.setString(1, subscriptionId);
                s.setString(2, owner.businessId());
                s.setString(3, admin.getId());
                s.setString(4, admin.getId());
                s.executeUpdate();
            }
            try (PreparedStatement s = a.prepareStatement("update plan_request set status = 'FULFILLED', subscription_id = ?, resolved_at = now(), "
                    + "resolved_by_user_id = ?, row_version = row_version + 1 where id = ?")) {
                s.setString(1, subscriptionId);
                s.setString(2, admin.getId());
                s.setString(3, id);
                s.executeUpdate();
            }
            Future<String> change = async(put("/api/admin/monetization-config").header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"setting\":\"SETTING_2\",\"models\":[\"MODEL_2\"],\"expectedVersion\":1}"));
            waitForConfigLock();
            a.commit();
            assertThat(change.get(30, TimeUnit.SECONDS)).isEqualTo("200 ");
        }
        assertThat(jdbc.queryForObject("select config_version from business_subscription where business_id = ?", Integer.class, owner.businessId())).isEqualTo(1);
        String view = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/businesses/{id}/subscription",
                owner.businessId()).header("Authorization", adminBearer)).andReturn().getResponse().getContentAsString();
        assertThat((Boolean) JsonPath.read(view, "$.current.outsideCurrentConfiguration")).isTrue();
    }

    @Test
    void locksAreTakenInTheSameOrderEverywhereSoConcurrentMixedActionsFinish() throws Exception {
        List<Future<String>> results = new ArrayList<>();
        String id = pendingRequest(jdbc, data.tenant("mixed-a").businessId(), "MODEL_4", 1, owner.owner().getId());
        CyclicBarrier barrier = new CyclicBarrier(3);
        results.add(pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return String.valueOf(mockMvc.perform(fulfil(id, 1, 0)).andReturn().getResponse().getStatus()); }));
        results.add(pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return String.valueOf(mockMvc.perform(adminRecord("MODEL_2", 1)).andReturn().getResponse().getStatus()); }));
        results.add(pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return String.valueOf(mockMvc.perform(put("/api/admin/monetization-config")
                .header("Authorization", adminBearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"setting\":\"SETTING_2\",\"models\":[\"MODEL_2\",\"MODEL_3\"],\"expectedVersion\":1}")).andReturn().getResponse().getStatus()); }));
        for (Future<String> f : results) assertThat(f.get(30, TimeUnit.SECONDS)).isIn("200", "201", "409", "400"); // no deadlock, no 500
    }
}
