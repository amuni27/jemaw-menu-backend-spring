package com.agafari.com.selfservice;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.PlanRequestService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.agafari.com.selfservice.SelfServiceActivationTest.body;
import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF Self-Service Phase A (SS-AC4, SS-3): concurrent and duplicate activations give exactly one subscription; an
 * activation racing a Super Admin manual record or a request fulfilment gives one consistent outcome; when creating the
 * subscription fails, the named pending request is not withdrawn (one transaction).
 */
class SelfServiceRaceTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanRequestService planRequests;

    private User admin;
    private Tenant owner;

    @BeforeEach
    void setUp() {
        admin = data.admin("ss-race-admin");
        owner = data.tenant("ss-race-owner");
        jdbc.update("update users set email_verified_at = now() where id = ?", owner.owner().getId());
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        planRequests.setOwnerPlansVisible(true);
        planRequests.setSelfServiceEnabled(true);
    }

    @AfterEach
    void reset() {
        planRequests.setOwnerPlansVisible(false);
        planRequests.setSelfServiceEnabled(false);
        jdbc.execute("drop trigger if exists test_failing_owner_subscription on business_subscription");
        jdbc.execute("drop function if exists test_failing_owner_subscription()");
    }

    private int activateStatus(String json) throws Exception {
        return mockMvc.perform(post("/api/plan/activations").header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn().getResponse().getStatus();
    }

    private int current() {
        return jdbc.queryForObject("select count(*) from business_subscription where business_id = ? and status <> 'EXPIRED'", Integer.class, owner.businessId());
    }

    private <T> List<T> together(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) futures.add(pool.submit(() -> { start.await(); return task.call(); }));
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) results.add(f.get());
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelDuplicateActivationsCreateExactlyOneSubscription() throws Exception {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) tasks.add(() -> activateStatus(body("MODEL_2", 1, null, null)));
        List<Integer> statuses = together(tasks);
        assertThat(statuses).containsOnly(201, 200);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(current()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = 'OWNER_SUBSCRIPTION_ACTIVATED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void anActivationRacingASuperAdminManualRecordLeavesExactlyOneCurrentSubscription() throws Exception {
        List<Integer> statuses = together(List.of(
                () -> activateStatus(body("MODEL_2", 1, null, null)),
                () -> mockMvc.perform(post("/api/admin/businesses/{id}/subscription", owner.businessId()).header("Authorization", data.adminBearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planCode\":\"MODEL_4\",\"expectedConfigVersion\":1}")).andReturn().getResponse().getStatus()));
        assertThat(current()).isEqualTo(1);
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
    }

    @Test
    void anActivationRacingTheFulfilmentOfTheNamedRequestGivesOneOutcome() throws Exception {
        planRequests.setSelfServiceEnabled(false);
        mockMvc.perform(post("/api/plan/requests").header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"planCode\":\"MODEL_4\",\"expectedConfigVersion\":1}"));
        planRequests.setSelfServiceEnabled(true);
        Map<String, Object> pending = jdbc.queryForMap("select id, row_version from plan_request where business_id = ? and status = 'PENDING'", owner.businessId());
        String id = (String) pending.get("id");
        int rv = (Integer) pending.get("row_version");
        together(List.of(
                () -> activateStatus(body("MODEL_2", 1, id, rv)),
                () -> mockMvc.perform(post("/api/admin/plan-requests/{id}/fulfil", id).header("Authorization", data.adminBearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedConfigVersion\":1,\"rowVersion\":" + rv + "}")).andReturn().getResponse().getStatus()));
        assertThat(current()).isEqualTo(1);
        String requestStatus = jdbc.queryForObject("select status from plan_request where id = ?", String.class, id);
        String plan = jdbc.queryForObject("select plan_code from business_subscription where business_id = ? and status <> 'EXPIRED'", String.class, owner.businessId());
        // either the owner won (request withdrawn, Free with Ads) or the admin won (request fulfilled, Ad-Free): never both, never neither
        assertThat(requestStatus + "/" + plan).isIn("WITHDRAWN/MODEL_2", "FULFILLED/MODEL_4");
    }

    @Test
    void whenCreatingTheSubscriptionFailsTheNamedRequestStaysPending() throws Exception {
        planRequests.setSelfServiceEnabled(false);
        mockMvc.perform(post("/api/plan/requests").header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"planCode\":\"MODEL_4\",\"expectedConfigVersion\":1}"));
        planRequests.setSelfServiceEnabled(true);
        Map<String, Object> pending = jdbc.queryForMap("select id, row_version from plan_request where business_id = ? and status = 'PENDING'", owner.businessId());
        jdbc.execute("create function test_failing_owner_subscription() returns trigger language plpgsql as $$ begin "
                + "if new.source = 'OWNER' then raise exception 'test: subscription insert fails'; end if; return new; end $$");
        jdbc.execute("create trigger test_failing_owner_subscription before insert on business_subscription for each row execute function test_failing_owner_subscription()");
        int status = activateStatus(body("MODEL_2", 1, (String) pending.get("id"), (Integer) pending.get("row_version")));
        assertThat(status).isGreaterThanOrEqualTo(500);
        assertThat(jdbc.queryForObject("select status from plan_request where id = ?", String.class, pending.get("id"))).isEqualTo("PENDING");
        assertThat(current()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action in ('OWNER_SUBSCRIPTION_ACTIVATED', 'OWNER_PLAN_REQUEST_WITHDRAWN')",
                Integer.class)).isZero();
    }
}
