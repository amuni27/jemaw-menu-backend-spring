package com.agafari.com.selfservice;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.EntitlementService;
import com.agafari.com.service.Entitlements;
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
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF Self-Service Phase A (SS-1, SS-2, SS-3, SS-4; SS-AC1…AC8): an eligible owner starts Free with Ads in one step, behind
 * {@code app.monetization.self-service-enabled} (default off). Active at once, {@code source = OWNER}, open-ended, audited.
 * Every check is server-side and every rejection audited; paid plans never activate here (they keep the request flow);
 * Setting 1 offers no self-service. A pending plan request is withdrawn only when the owner names it (explicit
 * confirmation), atomically with the activation; never silently.
 */
class SelfServiceActivationTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanRequestService planRequests;
    @Autowired private EntitlementService entitlements;

    private User admin;
    private Tenant owner;

    @BeforeEach
    void setUp() {
        admin = data.admin("ss-admin");
        owner = data.tenant("ss-owner");
        verify(owner);
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        planRequests.setOwnerPlansVisible(true);
        planRequests.setSelfServiceEnabled(true);
        entitlements.setEnabled(true);
    }

    @AfterEach
    void reset() {
        planRequests.setOwnerPlansVisible(false);
        planRequests.setSelfServiceEnabled(false);
        entitlements.setEnabled(false);
    }

    private void verify(Tenant t) {
        jdbc.update("update users set email_verified_at = now() where id = ?", t.owner().getId());
    }

    static String body(String plan, int version, String requestId, Integer rowVersion) {
        return "{\"planCode\":\"" + plan + "\",\"expectedConfigVersion\":" + version
                + (requestId == null ? "" : ",\"withdrawPendingRequestId\":\"" + requestId + "\",\"withdrawPendingRequestRowVersion\":" + rowVersion) + "}";
    }

    private ResultActions activate(Tenant t, String json) throws Exception {
        return mockMvc.perform(post("/api/plan/activations").header("Authorization", data.bearer(t)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions activate(String plan, int version) throws Exception {
        return activate(owner, body(plan, version, null, null));
    }

    private ResultActions requestPlan(String plan, int version) throws Exception {
        return mockMvc.perform(post("/api/plan/requests").header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"planCode\":\"" + plan + "\",\"expectedConfigVersion\":" + version + "}"));
    }

    private int current() {
        return jdbc.queryForObject("select count(*) from business_subscription where business_id = ? and status <> 'EXPIRED'", Integer.class, owner.businessId());
    }

    private List<String> rejections() {
        return jdbc.queryForList("select detail from admin_audit_event where action = 'OWNER_ACTIVATION_REJECTED' order by occurred_at", String.class);
    }

    private int audits(String action) {
        return jdbc.queryForObject("select count(*) from admin_audit_event where action = ?", Integer.class, action);
    }

    @Test
    void ss1AnEligibleOwnerStartsFreeWithAdsAtOnceAsAnOwnerSourcedOpenEndedSubscription() throws Exception {
        mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$.selfService.available").value(true))
                .andExpect(jsonPath("$.selfService.verificationRequired").value(false));

        String json = activate("MODEL_2", 1).andExpect(status().isCreated())
                .andExpect(jsonPath("$.planCode").value("MODEL_2"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.created").value(true))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(json, "$.subscriptionId");
        Map<String, Object> row = jdbc.queryForMap("select plan_code, status, source, period_end, config_version, created_by_user_id from business_subscription where id = ?", id);
        assertThat(row).containsEntry("plan_code", "MODEL_2").containsEntry("status", "ACTIVE").containsEntry("source", "OWNER")
                .containsEntry("config_version", 1).containsEntry("created_by_user_id", owner.owner().getId());
        assertThat(row.get("period_end")).isNull();
        assertThat(audits("OWNER_SUBSCRIPTION_ACTIVATED")).isEqualTo(1);

        // entitlements follow at once (existing service, unchanged); the owner and admin labels (SS-4)
        assertThat(entitlements.forBusiness(owner.businessId()).adSource()).isEqualTo(Entitlements.AdSource.AGAFARI);
        mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$.current.planCode").value("MODEL_2"))
                .andExpect(jsonPath("$.current.sourceLabel").value("Started by you"))
                .andExpect(jsonPath("$.selfService.available").value(false));
        mockMvc.perform(get("/api/admin/businesses/{id}/subscription", owner.businessId()).header("Authorization", data.adminBearer(admin)))
                .andExpect(jsonPath("$.current.source").value("OWNER"))
                .andExpect(jsonPath("$.current.sourceLabel").value("Self-service (owner), no payment"))
                .andExpect(jsonPath("$.current.paymentVerified").value(false));
    }

    @Test
    void ss1ADuplicateClickReturnsTheSameSubscriptionAndCreatesNoSecondRow() throws Exception {
        String first = JsonPath.read(activate("MODEL_2", 1).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.subscriptionId");
        activate("MODEL_2", 1).andExpect(status().isOk()).andExpect(jsonPath("$.subscriptionId").value(first)).andExpect(jsonPath("$.created").value(false));
        assertThat(current()).isEqualTo(1);
        assertThat(audits("OWNER_SUBSCRIPTION_ACTIVATED")).isEqualTo(1);
    }

    @Test
    void ss1WithTheFlagOffNothingChangesAndActivationIsRefused() throws Exception {
        planRequests.setSelfServiceEnabled(false);
        mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(owner))).andExpect(jsonPath("$.selfService").doesNotExist());
        activate("MODEL_2", 1).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SELF_SERVICE_DISABLED"));
        requestPlan("MODEL_2", 1).andExpect(status().isCreated()); // today's request flow, unchanged
        assertThat(current()).isZero();
        assertThat(rejections()).containsExactly("disabled");

        planRequests.setSelfServiceEnabled(true);
        planRequests.setOwnerPlansVisible(false); // plans hidden: nothing to start either
        activate("MODEL_2", 1).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SELF_SERVICE_DISABLED"));
    }

    @Test
    void ss1PaidPlansNeverActivateHereAndKeepTheRequestFlowWhileFreeRequestsPointToSelfService() throws Exception {
        activate("MODEL_3", 1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PLAN_REQUIRES_PAYMENT"));
        activate("MODEL_4", 1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PLAN_REQUIRES_PAYMENT"));
        activate("MODEL_1", 1).andExpect(status().is4xxClientError());
        assertThat(current()).isZero();
        assertThat(rejections()).startsWith("requires_payment", "requires_payment");

        requestPlan("MODEL_2", 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USE_SELF_SERVICE"));
        requestPlan("MODEL_4", 1).andExpect(status().isCreated()); // paid: request, unchanged
        assertThat(jdbc.queryForList("select detail from admin_audit_event where action = 'OWNER_PLAN_REQUEST_REJECTED'", String.class)).contains("use_self_service");
    }

    @Test
    void ss1EligibilityIsCheckedOnTheServerAndEveryRejectionIsAudited() throws Exception {
        activate("MODEL_2", 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIG_VERSION_CONFLICT"));
        activate("BOGUS", 1).andExpect(status().isBadRequest());

        config(jdbc, 2, "SETTING_2", false, true, true, admin.getId()); // Free with Ads no longer enabled
        activate("MODEL_2", 2).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_NOT_ENABLED"));
        mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(owner))).andExpect(jsonPath("$.selfService.available").value(false));

        config(jdbc, 3, "SETTING_1", false, false, false, admin.getId()); // Model 1 only: no self-service
        activate("MODEL_2", 3).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SETTING_NOT_SELF_SERVICE"));
        assertThat(current()).isZero();
        assertThat(rejections()).containsExactly("version_conflict", "invalid", "not_enabled", "setting1");
    }

    @Test
    void ss1NotConfiguredMeansNothingToStart() throws Exception {
        jdbc.execute("TRUNCATE TABLE monetization_config CASCADE");
        activate("MODEL_2", 0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MONETIZATION_NOT_CONFIGURED"));
        assertThat(rejections()).containsExactly("not_configured");
    }

    @Test
    void ss1AnExistingSubscriptionOfAnyKindIsNeverReplaced() throws Exception {
        String manual = subscription(jdbc, owner.businessId(), "MODEL_4", 1, admin.getId());
        activate("MODEL_2", 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_EXISTS"));
        mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(owner))).andExpect(jsonPath("$.selfService.available").value(false));

        // grandfathered: the plan is no longer offered, the subscription stays (E4-M6)
        config(jdbc, 2, "SETTING_2", true, true, false, admin.getId());
        activate("MODEL_2", 2).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_EXISTS"));
        assertThat(jdbc.queryForObject("select status from business_subscription where id = ?", String.class, manual)).isEqualTo("ACTIVE");
        assertThat(current()).isEqualTo(1);
        assertThat(rejections()).containsExactly("exists", "exists");
    }

    @Test
    void ss2AnUnverifiedEmailCantStartFreeWhileVerificationIsOperational() throws Exception {
        Tenant unverified = data.tenant("ss-unverified"); // the test profile has verification operational (secret, link, mailer)
        mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(unverified)))
                .andExpect(jsonPath("$.selfService.available").value(true))
                .andExpect(jsonPath("$.selfService.verificationRequired").value(true));
        activate(unverified, body("MODEL_2", 1, null, null)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"))
                .andExpect(jsonPath("$.message").value("Verify your email to start Free with Ads."));
        assertThat(rejections()).containsExactly("unverified");
        verify(unverified);
        activate(unverified, body("MODEL_2", 1, null, null)).andExpect(status().isCreated());
    }

    @Test
    void authorizationTheRestaurantComesFromTheTokenAndOtherRolesAreRefused() throws Exception {
        mockMvc.perform(post("/api/plan/activations").contentType(MediaType.APPLICATION_JSON).content(body("MODEL_2", 1, null, null)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/plan/activations").header("Authorization", data.adminBearer(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(body("MODEL_2", 1, null, null))).andExpect(status().isForbidden());
        Tenant other = data.tenant("ss-other");
        String json = body("MODEL_2", 1, null, null);
        activate(owner, json.substring(0, json.length() - 1) + ",\"businessId\":\"" + other.businessId() + "\"}").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select count(*) from business_subscription where business_id = ?", Integer.class, other.businessId())).isZero();
        assertThat(current()).isEqualTo(1);
    }

    @Test
    void ss3APendingRequestIsWithdrawnOnlyWhenTheOwnerNamesItAndAtomicallyWithTheActivation() throws Exception {
        planRequests.setSelfServiceEnabled(false);
        requestPlan("MODEL_4", 1).andExpect(status().isCreated());
        planRequests.setSelfServiceEnabled(true);
        Map<String, Object> pending = jdbc.queryForMap("select id, row_version from plan_request where business_id = ? and status = 'PENDING'", owner.businessId());
        String requestId = (String) pending.get("id");
        int rowVersion = (Integer) pending.get("row_version");
        mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(owner)))
                .andExpect(jsonPath("$.pendingRequest.id").value(requestId))
                .andExpect(jsonPath("$.selfService.available").value(true));

        // not named: refused, nothing withdrawn
        activate("MODEL_2", 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_REQUEST_PENDING"));
        // named with a stale version or another id: refused, nothing withdrawn
        activate(owner, body("MODEL_2", 1, requestId, rowVersion + 1)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_REQUEST_CHANGED"));
        activate(owner, body("MODEL_2", 1, "another-request", rowVersion)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_REQUEST_CHANGED"));
        assertThat(jdbc.queryForObject("select status from plan_request where id = ?", String.class, requestId)).isEqualTo("PENDING");
        assertThat(current()).isZero();
        assertThat(rejections()).containsExactly("pending", "request_changed", "request_changed");

        // named exactly as the owner saw it: withdrawn and started in one transaction, both audited
        String json = activate(owner, body("MODEL_2", 1, requestId, rowVersion)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.withdrawnRequestId").value(requestId)).andReturn().getResponse().getContentAsString();
        assertThat(jdbc.queryForMap("select status, resolved_by_user_id from plan_request where id = ?", requestId))
                .containsEntry("status", "WITHDRAWN").containsEntry("resolved_by_user_id", owner.owner().getId());
        assertThat(jdbc.queryForObject("select detail from admin_audit_event where action = 'OWNER_PLAN_REQUEST_WITHDRAWN' order by occurred_at desc limit 1", String.class))
                .contains("withdrawn by owner to start Free with Ads");
        assertThat(jdbc.queryForObject("select detail from admin_audit_event where action = 'OWNER_SUBSCRIPTION_ACTIVATED'", String.class))
                .contains("withdrawn request " + requestId);
        assertThat((String) JsonPath.read(json, "$.subscriptionId")).isNotBlank();
        assertThat(current()).isEqualTo(1);
    }

    @Test
    void ss3WhenTheSuperAdminResolvesTheRequestFirstTheOwnersConfirmationIsRefused() throws Exception {
        planRequests.setSelfServiceEnabled(false);
        requestPlan("MODEL_4", 1).andExpect(status().isCreated());
        planRequests.setSelfServiceEnabled(true);
        Map<String, Object> pending = jdbc.queryForMap("select id, row_version from plan_request where business_id = ? and status = 'PENDING'", owner.businessId());
        String requestId = (String) pending.get("id");
        int rowVersion = (Integer) pending.get("row_version");
        mockMvc.perform(post("/api/admin/plan-requests/{id}/decline", requestId).header("Authorization", data.adminBearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"rowVersion\":" + rowVersion + "}")).andExpect(status().isOk());
        activate(owner, body("MODEL_2", 1, requestId, rowVersion)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_REQUEST_CHANGED"));
        assertThat(current()).isZero();
        activate("MODEL_2", 1).andExpect(status().isCreated()); // declined: nothing pending any more, the owner can start free

        Tenant fulfilled = data.tenant("ss-fulfilled");
        verify(fulfilled);
        planRequests.setSelfServiceEnabled(false);
        mockMvc.perform(post("/api/plan/requests").header("Authorization", data.bearer(fulfilled)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"planCode\":\"MODEL_4\",\"expectedConfigVersion\":1}")).andExpect(status().isCreated());
        planRequests.setSelfServiceEnabled(true);
        Map<String, Object> second = jdbc.queryForMap("select id, row_version from plan_request where business_id = ? and status = 'PENDING'", fulfilled.businessId());
        mockMvc.perform(post("/api/admin/plan-requests/{id}/fulfil", second.get("id")).header("Authorization", data.adminBearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedConfigVersion\":1,\"rowVersion\":" + second.get("row_version") + "}"))
                .andExpect(status().isOk());
        activate(fulfilled, body("MODEL_2", 1, (String) second.get("id"), (Integer) second.get("row_version")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_EXISTS"));
    }
}
