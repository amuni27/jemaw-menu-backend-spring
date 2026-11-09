package com.agafari.com.subscription;

import com.agafari.com.dto.request.PlanRequestCreateRequest;
import com.agafari.com.dto.request.PlanRequestFulfilRequest;
import com.agafari.com.dto.request.SubscriptionActivationRequest;
import com.agafari.com.dto.request.TrialStartRequest;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.Entitlements;
import com.agafari.com.service.EntitlementService;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.service.PlanRequestService;
import com.agafari.com.service.SelfServiceActivationService;
import com.agafari.com.service.TrialService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.MonetizationFixtures;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** AGF SUB-R2-07 (MT-05): expiry at read time with no job running, and materialize before create. Grace of 7 days is a fixture. */
class EffectiveStateReadTimeTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntitlementService entitlements;
    @Autowired private EffectiveStateEvaluator evaluator;
    @Autowired private MonetizationClock clock;
    @Autowired private SubscriptionLifecycle lifecycle;
    @Autowired private PlanRequestService planRequests;
    @Autowired private SelfServiceActivationService selfService;
    @Autowired private TrialService trials;
    @Autowired private MonetizationLocks locks;
    @Autowired private PlatformTransactionManager transactions;

    private User admin;
    private Tenant owner;
    private String bearer;

    @BeforeEach
    void setUp() {
        admin = data.admin("effective-admin");
        bearer = data.adminBearer(admin);
        owner = data.tenant("effective-owner");
        entitlements.setEnabled(true);
        evaluator.setPeriodEndEnabled(true);
    }

    @AfterEach
    void reset() {
        entitlements.setEnabled(false);
        evaluator.setPeriodEndEnabled(false);
        clock.reset();
        trials.setEnabled(false);
        planRequests.setOwnerPlansVisible(false);
        planRequests.setSelfServiceEnabled(false);
    }

    private static Instant days(int n) {
        return Instant.now().plus(n, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    }

    private String row(String businessId, String source, String status, String plan, Instant end, Integer grace) {
        String id = UUID.randomUUID().toString();
        boolean provider = "PROVIDER".equals(source);
        // AGF SUB-R4-02: a fixture row has no payment evidence, so the commit check stays off for this test; the per-test reset restores it.
        if (provider) jdbc.execute("alter table business_subscription disable trigger provider_row_has_evidence");
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, created_by_user_id, updated_at, updated_by_user_id, grace_days, row_version) "
                + "values (?, ?, ?, ?, 1, ?, ?, now(), ?, now(), ?, ?, 0)", id, businessId, plan, status, end == null ? null : Timestamp.from(end), source, admin.getId(), admin.getId(), grace);
        return id;
    }

    private void advanceDays(int n) {
        clock.setOffset(Duration.ofDays(n));
    }

    private Map<String, Object> stored(String id) {
        return jdbc.queryForMap("select status, ended_reason, ended_at, past_due_since, row_version from business_subscription where id = ?", id);
    }

    private int create() throws Exception {
        return mockMvc.perform(post("/api/admin/businesses/{id}/subscription", owner.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"planCode\":\"MODEL_2\",\"expectedConfigVersion\":1}")).andReturn().getResponse().getStatus();
    }

    private void materializeCommitted() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            locks.configShared();
            locks.restaurant(owner.businessId());
            lifecycle.materialize(owner.businessId());
        });
    }

    private int audits(String id) {
        return jdbc.queryForObject("select count(*) from admin_audit_event where target_id = ? and action in ('SUBSCRIPTION_PERIOD_ENDED', 'SUBSCRIPTION_PAST_DUE')", Integer.class, id);
    }

    private int currentRows() {
        return jdbc.queryForObject("select count(*) from business_subscription where business_id = ? and status <> 'EXPIRED'", Integer.class, owner.businessId());
    }

    // ---- MT-05: no job is running in this context, and reads never write

    @Test
    void manualPastItsEndIsNotEntitledAndNothingIsWrittenByTheRead() {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        String id = row(owner.businessId(), "MANUAL", "ACTIVE", "MODEL_4", days(5), null);
        assertThat(entitlements.forBusiness(owner.businessId()).planCode()).isEqualTo("MODEL_4");
        advanceDays(6);
        assertThat(entitlements.forBusiness(owner.businessId())).isEqualTo(Entitlements.unrestricted());
        assertThat(stored(id)).containsEntry("status", "ACTIVE").containsEntry("ended_reason", null).containsEntry("row_version", 0);
    }

    @Test
    void ownerWithAnEndExpiresAndOwnerWithoutOneStays() {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        row(owner.businessId(), "OWNER", "ACTIVE", "MODEL_2", days(5), null);
        Tenant open = data.tenant("effective-open");
        row(open.businessId(), "OWNER", "ACTIVE", "MODEL_2", null, null);
        advanceDays(6);
        assertThat(entitlements.forBusiness(owner.businessId())).isEqualTo(Entitlements.unrestricted());
        assertThat(entitlements.forBusiness(open.businessId()).planCode()).isEqualTo("MODEL_2");
    }

    @Test
    void aTrialBehavesAsToday() {
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        String id = row(owner.businessId(), "TRIAL", "TRIAL", "MODEL_1", days(5), null);
        assertThat(entitlements.forBusiness(owner.businessId()).status()).isEqualTo("TRIAL");
        advanceDays(6);
        assertThat(entitlements.forBusiness(owner.businessId())).isEqualTo(Entitlements.unrestricted());
        assertThat(stored(id)).containsEntry("status", "TRIAL");
    }

    @Test
    void providerGraceAndExpiryAtReadTime() {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        row(owner.businessId(), "PROVIDER", "ACTIVE", "MODEL_4", days(5), 7);
        advanceDays(6);
        assertThat(entitlements.forBusiness(owner.businessId()).status()).isEqualTo("PAST_DUE");
        advanceDays(13);
        assertThat(entitlements.forBusiness(owner.businessId())).isEqualTo(Entitlements.unrestricted());
    }

    @Test
    void flagOffChangesNothingForAPassedManualEnd() throws Exception {
        evaluator.setPeriodEndEnabled(false);
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        String id = row(owner.businessId(), "MANUAL", "ACTIVE", "MODEL_4", days(5), null);
        advanceDays(6);
        assertThat(entitlements.forBusiness(owner.businessId()).planCode()).isEqualTo("MODEL_4");
        assertThat(create()).isEqualTo(409);
        assertThat(stored(id)).containsEntry("status", "ACTIVE").containsEntry("row_version", 0);
        assertThat(audits(id)).isZero();
    }

    // ---- materialize before create (AC3)

    @Test
    void aSuccessorCanBeCreatedAfterAnEffectivelyExpiredRow() throws Exception {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        Instant end = days(5);
        String id = row(owner.businessId(), "MANUAL", "ACTIVE", "MODEL_4", end, null);
        advanceDays(6);
        assertThat(create()).isEqualTo(201);
        Map<String, Object> old = stored(id);
        assertThat(old).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "PERIOD_END").containsEntry("row_version", 1);
        assertThat(((Timestamp) old.get("ended_at")).toInstant()).isEqualTo(end);
        assertThat(audits(id)).isEqualTo(1);
        assertThat(currentRows()).isEqualTo(1);
    }

    @Test
    void aRunningRowStillBlocksACreate() throws Exception {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        String id = row(owner.businessId(), "MANUAL", "ACTIVE", "MODEL_4", days(5), null);
        assertThat(create()).isEqualTo(409);
        assertThat(stored(id)).containsEntry("status", "ACTIVE").containsEntry("row_version", 0);
    }

    @Test
    void anOpenEndedOwnerRowStillBlocksACreate() throws Exception {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        row(owner.businessId(), "OWNER", "ACTIVE", "MODEL_2", null, null);
        advanceDays(400);
        assertThat(create()).isEqualTo(409);
    }

    @Test
    void aProviderRowInGraceIsPersistedPastDueOnceAndStillBlocks() throws Exception {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        Instant end = days(5);
        String id = row(owner.businessId(), "PROVIDER", "ACTIVE", "MODEL_4", end, 7);
        advanceDays(6);
        assertThat(create()).isEqualTo(409);
        assertThat(stored(id)).as("the refused create rolled its work back; the read-time state doesn't depend on it").containsEntry("status", "ACTIVE");
        materializeCommitted();
        materializeCommitted();
        Map<String, Object> row = stored(id);
        assertThat(row).containsEntry("status", "PAST_DUE").containsEntry("row_version", 1);
        assertThat(((Timestamp) row.get("past_due_since")).toInstant()).isEqualTo(end);
        assertThat(audits(id)).isEqualTo(1);
    }

    @Test
    void aProviderRowPastItsGraceEndsAtTheGraceEnd() throws Exception {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        Instant end = days(5);
        String id = row(owner.businessId(), "PROVIDER", "ACTIVE", "MODEL_4", end, 7);
        advanceDays(13);
        assertThat(create()).isEqualTo(201);
        Map<String, Object> row = stored(id);
        assertThat(row).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "PAYMENT_FAILED").containsEntry("past_due_since", null);
        assertThat(((Timestamp) row.get("ended_at")).toInstant()).isEqualTo(end.plus(Duration.ofDays(7)));
    }

    @Test
    void anOwnerPlanRequestIsNotBlockedByAnExpiredRow() {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        planRequests.setOwnerPlansVisible(true);
        String id = row(owner.businessId(), "MANUAL", "ACTIVE", "MODEL_4", days(5), null);
        advanceDays(6);
        planRequests.request(owner.businessId(), new PlanRequestCreateRequest("MODEL_4", 1), owner.owner().getId(), "o@example.test");
        assertThat(stored(id)).containsEntry("status", "EXPIRED");
    }

    @Test
    void fulfilmentIsNotBlockedByAnExpiredRow() {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        String request = MonetizationFixtures.pendingRequest(jdbc, owner.businessId(), "MODEL_4", 1, owner.owner().getId()); // first: V14 refuses a pending request beside a current row
        String id = row(owner.businessId(), "MANUAL", "ACTIVE", "MODEL_4", days(5), null);
        advanceDays(6);
        planRequests.fulfil(request, new PlanRequestFulfilRequest(1, days(40), 0), admin.getId(), "a@example.test");
        assertThat(stored(id)).containsEntry("status", "EXPIRED");
        assertThat(currentRows()).isEqualTo(1);
    }

    @Test
    void selfServiceIsNotBlockedByAnExpiredRow() {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        planRequests.setOwnerPlansVisible(true);
        planRequests.setSelfServiceEnabled(true);
        jdbc.update("update users set email_verified_at = now() where id = ?", owner.owner().getId());
        String id = row(owner.businessId(), "MANUAL", "ACTIVE", "MODEL_4", days(5), null);
        advanceDays(6);
        selfService.activate(owner.businessId(), new SubscriptionActivationRequest("MODEL_2", 1, null, null), owner.owner().getId(), "o@example.test");
        assertThat(stored(id)).containsEntry("status", "EXPIRED");
        assertThat(currentRows()).isEqualTo(1);
    }

    @Test
    void anAdminTrialStartIsNotBlockedByAnExpiredRow() {
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        trials.setEnabled(true);
        String id = row(owner.businessId(), "MANUAL", "ACTIVE", "MODEL_4", days(5), null);
        advanceDays(6);
        trials.start(owner.businessId(), new TrialStartRequest(1), admin.getId(), "a@example.test");
        assertThat(stored(id)).containsEntry("status", "EXPIRED");
        assertThat(currentRows()).isEqualTo(1);
    }

    @Test
    void materializeRefusesToRunWithoutTheLocks() {
        assertThatThrownBy(() -> lifecycle.materialize(owner.businessId())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withTheFlagOffMaterializeDoesNothingAtAll() {
        evaluator.setPeriodEndEnabled(false);
        lifecycle.materialize(owner.businessId()); // no locks held, and none needed: it returns before touching anything
    }

    // ---- expiry against an admin extension at the boundary

    @Test
    void aCreateRacingAnAdminExtensionHasOneConsistentOutcome() throws Exception {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                owner = data.tenant("effective-race-" + round);
                clock.reset();
                String id = row(owner.businessId(), "MANUAL", "ACTIVE", "MODEL_4", days(1), null);
                advanceDays(2);
                // a row with edits behind it: the successor starts at version 0, so a stale request can't match it by chance (see the report)
                jdbc.update("update business_subscription set row_version = 3 where id = ?", id);
                String extended = days(30).toString();
                CyclicBarrier barrier = new CyclicBarrier(2);
                List<Future<Integer>> f = new ArrayList<>();
                f.add(pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return create(); }));
                f.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return mockMvc.perform(patch("/api/admin/businesses/{id}/subscription", owner.businessId()).header("Authorization", bearer)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"periodEnd\":\"" + extended + "\",\"rowVersion\":3}")).andReturn().getResponse().getStatus();
                }));
                int createStatus = f.get(0).get(30, TimeUnit.SECONDS);
                int patchStatus = f.get(1).get(30, TimeUnit.SECONDS);
                assertThat(currentRows()).as("round " + round).isEqualTo(1);
                String status = (String) stored(id).get("status");
                if (createStatus == 201) {
                    assertThat(status).as("round " + round).isEqualTo("EXPIRED");
                    assertThat(patchStatus).as("round " + round).isIn(404, 409);
                } else {
                    assertThat(createStatus).as("round " + round).isEqualTo(409);
                    assertThat(patchStatus).as("round " + round).isEqualTo(200);
                    assertThat(status).as("round " + round).isEqualTo("ACTIVE");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
