package com.agafari.com.trial;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.PlanRequestService;
import com.agafari.com.service.TrialService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.RecordingEmailVerificationMailer;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import static com.agafari.com.support.MonetizationFixtures.pendingRequest;
import static com.agafari.com.support.MonetizationFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF BE-1 (register §45 D-3, §46 Q1/Q2): an owner starts the Model 1 free trial themselves with {@code POST /api/plan/trial},
 * only under Setting 1 with {@code trials-enabled} (and the owner plan view on). The restaurant and the owner always come
 * from the session; the existing eligibility and lifecycle rules apply (no current subscription, no pending request, one
 * trial per restaurant ever, the configuration version the owner saw). A repeated or concurrent click gives the same
 * trial. Email verification is required only while verification is operational (Q2, as SS-2). A pending registration
 * intent is honoured and resolved by the start (Q1). {@code GET /api/plan} gains {@code trialEligible} only while trials
 * are enabled. Registration trials and the admin start are unchanged (their own tests).
 */
class OwnerTrialStartTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TrialService trials;
    @Autowired private PlanRequestService planRequests;
    @Autowired private RecordingEmailVerificationMailer mailer;
    @Autowired private ObjectMapper mapper;

    private User admin;
    private Tenant owner;

    @BeforeEach
    void setUp(TestInfo test) {
        admin = data.admin("ot-admin");
        owner = data.tenant("ot-owner");
        verify(owner);
        // monetization_config is append-only: the not-configured case simply never gets one.
        if (!test.getTestMethod().orElseThrow().getName().equals("withoutAConfigurationNothingStarts"))
            config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        planRequests.setOwnerPlansVisible(true);
        trials.setEnabled(true);
    }

    @AfterEach
    void reset() {
        planRequests.setOwnerPlansVisible(false);
        trials.setEnabled(false);
        mailer.setOperational(true);
    }

    private void verify(Tenant t) {
        jdbc.update("update users set email_verified_at = now() where id = ?", t.owner().getId());
    }

    private ResultActions start(Tenant t, String json) throws Exception {
        return mockMvc.perform(post("/api/plan/trial").header("Authorization", data.bearer(t)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions start(Tenant t, int version) throws Exception {
        return start(t, "{\"expectedConfigVersion\":" + version + "}");
    }

    private JsonNode view(Tenant t) throws Exception {
        return mapper.readTree(mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(t))).andReturn().getResponse().getContentAsString());
    }

    private int trialRows(Tenant t) {
        return jdbc.queryForObject("select count(*) from business_subscription where business_id = ? and source = 'TRIAL'", Integer.class, t.businessId());
    }

    private List<String> audit(String action) {
        return jdbc.queryForList("select coalesce(actor_user_id, '') || '|' || outcome || '|' || coalesce(detail, '') from admin_audit_event where action = ? order by occurred_at",
                String.class, action);
    }

    private String expiredTrial(Tenant t) {
        String id = UUID.randomUUID().toString();
        Instant start = Instant.now().minus(Duration.ofDays(40));
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, "
                + "created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_1', 'TRIAL', 1, ?, 'TRIAL', ?, 'system', ?, 'system', 0)",
                id, t.businessId(), Timestamp.from(start.plus(Duration.ofDays(30))), Timestamp.from(start), Timestamp.from(start));
        return id;
    }

    // ---- eligible ----

    @Test
    void anEligibleOwnerStartsOneThirtyDayTrialThatIsAuditedAsTheirs() throws Exception {
        assertThat(view(owner).path("trialEligible").asBoolean(false)).isTrue();

        start(owner, 1).andExpect(status().isCreated()).andExpect(jsonPath("$.planCode").value("MODEL_1")).andExpect(jsonPath("$.status").value("TRIAL"))
                .andExpect(jsonPath("$.created").value(true));

        Map<String, Object> row = jdbc.queryForMap("select * from business_subscription where business_id = ? and source = 'TRIAL'", owner.businessId());
        assertThat(row).containsEntry("plan_code", "MODEL_1").containsEntry("status", "TRIAL").containsEntry("config_version", 1)
                .containsEntry("created_by_user_id", owner.owner().getId());
        Instant created = ((Timestamp) row.get("created_at")).toInstant(), end = ((Timestamp) row.get("period_end")).toInstant();
        assertThat(Duration.between(created, end)).as("E4-Q2a: 30 days").isEqualTo(Duration.ofDays(30));
        assertThat(audit(TrialService.STARTED)).singleElement().asString().startsWith(owner.owner().getId() + "|SUCCESS|").contains("MODEL_1 TRIAL config=1").contains("by owner");
        JsonNode after = view(owner);
        assertThat(after.path("trialEligible").asBoolean(true)).as("used now").isFalse();
        assertThat(after.path("trial").path("effectiveStatus").asString()).isEqualTo("TRIAL");
    }

    @Test
    void aRepeatedClickReturnsTheSameTrial() throws Exception {
        String first = mapper.readTree(start(owner, 1).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("subscriptionId").asString();
        start(owner, 1).andExpect(status().isOk()).andExpect(jsonPath("$.subscriptionId").value(first)).andExpect(jsonPath("$.created").value(false));
        assertThat(trialRows(owner)).isEqualTo(1);
        assertThat(audit(TrialService.STARTED)).hasSize(1);
    }

    @Test
    void concurrentRequestsCreateExactlyOneTrial() throws Exception {
        CyclicBarrier go = new CyclicBarrier(4);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 4; i++) runs.add(pool.submit(() -> {
                go.await(10, TimeUnit.SECONDS);
                return start(owner, 1).andReturn().getResponse().getStatus();
            }));
            for (Future<Integer> r : runs) statuses.add(r.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsOnly(201, 200).filteredOn(s -> s == 201).hasSize(1);
        assertThat(trialRows(owner)).isEqualTo(1);
        assertThat(audit(TrialService.STARTED)).hasSize(1);
    }

    // ---- ineligible (each refused, nothing created, audited) ----

    private void refused(Tenant t, ResultActions result, int httpStatus, String code) throws Exception {
        result.andExpect(status().is(httpStatus)).andExpect(jsonPath("$.code").value(code));
        assertThat(audit(TrialService.REJECTED)).last().asString().startsWith(t.owner().getId() + "|FAILURE|");
    }

    @Test
    void trialsDisabledOrPlansHiddenRefuseAndHideEligibility() throws Exception {
        trials.setEnabled(false);
        assertThat(view(owner).has("trialEligible")).as("absent while trials are off: the response is unchanged").isFalse();
        refused(owner, start(owner, 1), 403, "TRIALS_DISABLED");
        trials.setEnabled(true);
        planRequests.setOwnerPlansVisible(false);
        refused(owner, start(owner, 1), 403, "TRIALS_DISABLED");
        assertThat(trialRows(owner)).isZero();
    }

    @Test
    void setting2DoesNotOfferATrial() throws Exception {
        config(jdbc, 2, "SETTING_2", true, false, true, admin.getId());
        assertThat(view(owner).path("trialEligible").asBoolean(true)).isFalse();
        refused(owner, start(owner, 2), 409, "TRIAL_NOT_OFFERED");
        assertThat(trialRows(owner)).isZero();
    }

    @Test
    void aStaleOrMissingConfigurationVersionIsRefused() throws Exception {
        refused(owner, start(owner, 7), 409, "CONFIG_VERSION_CONFLICT");
        start(owner, "{}").andExpect(status().isBadRequest());
        assertThat(trialRows(owner)).isZero();
    }

    @Test
    void anExistingSubscriptionOrPendingRequestBlocksTheTrial() throws Exception {
        Tenant withPlan = data.tenant("ot-with-plan");
        verify(withPlan);
        subscription(jdbc, withPlan.businessId(), "MODEL_1", 1, admin.getId());
        assertThat(view(withPlan).path("trialEligible").asBoolean(true)).isFalse();
        refused(withPlan, start(withPlan, 1), 409, "SUBSCRIPTION_EXISTS");

        Tenant pending = data.tenant("ot-pending");
        verify(pending);
        pendingRequest(jdbc, pending.businessId(), "MODEL_2", 1, pending.owner().getId()); // requests are Models 2-4 (e.g. left from a Setting 2 period)
        assertThat(view(pending).path("trialEligible").asBoolean(true)).isFalse();
        refused(pending, start(pending, 1), 409, "PLAN_REQUEST_PENDING");
        assertThat(trialRows(withPlan) + trialRows(pending)).isZero();
    }

    @Test
    void aRestaurantGetsOneTrialEver() throws Exception {
        expiredTrial(owner);
        assertThat(view(owner).path("trialEligible").asBoolean(true)).isFalse();
        refused(owner, start(owner, 1), 409, "TRIAL_ALREADY_USED");
        assertThat(trialRows(owner)).isEqualTo(1);
    }

    @Test
    void withoutAConfigurationNothingStarts() throws Exception {
        refused(owner, start(owner, 1), 400, "MONETIZATION_NOT_CONFIGURED");
    }

    // ---- Q2: email verification only while it is operational ----

    @Test
    void anUnverifiedOwnerIsRefusedOnlyWhileVerificationIsOperational() throws Exception {
        Tenant unverified = data.tenant("ot-unverified");
        refused(unverified, start(unverified, 1), 403, "EMAIL_NOT_VERIFIED");
        assertThat(trialRows(unverified)).isZero();

        mailer.setOperational(false); // no email provider yet (production before D5)
        start(unverified, 1).andExpect(status().isCreated());
        assertThat(trialRows(unverified)).isEqualTo(1);
    }

    // ---- identity from the session only ----

    @Test
    void theTrialIsAlwaysTheCallersOwnRestaurant() throws Exception {
        Tenant other = data.tenant("ot-other");
        verify(other);
        start(owner, "{\"expectedConfigVersion\":1,\"businessId\":\"" + other.businessId() + "\"}").andExpect(status().isCreated());
        assertThat(trialRows(owner)).isEqualTo(1);
        assertThat(trialRows(other)).as("a client-supplied id is ignored").isZero();
        assertThat(view(other).path("trialEligible").asBoolean(false)).isTrue();
    }

    @Test
    void onlyASignedInOwnerMayStartATrial() throws Exception {
        mockMvc.perform(post("/api/plan/trial").contentType(MediaType.APPLICATION_JSON).content("{\"expectedConfigVersion\":1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/plan/trial").header("Authorization", data.adminBearer(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"expectedConfigVersion\":1}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from business_subscription where source = 'TRIAL'", Integer.class)).isZero();
    }

    // ---- Q1: a pending registration intent is honoured ----

    @Test
    void aPendingRegistrationIntentIsResolvedByTheOwnersStartAndRecoveryAddsNothing() throws Exception {
        jdbc.update("insert into trial_intent (business_id, config_version, status, failed_attempts, created_at, row_version) values (?, 1, 'PENDING', 0, now(), 0)",
                owner.businessId());
        String id = mapper.readTree(start(owner, 1).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("subscriptionId").asString();

        assertThat(jdbc.queryForMap("select status, resolution, subscription_id from trial_intent where business_id = ?", owner.businessId()))
                .containsEntry("status", "STARTED").containsEntry("resolution", "MANUAL").containsEntry("subscription_id", id);
        trials.runRecovery();
        assertThat(trialRows(owner)).isEqualTo(1);
    }
}
