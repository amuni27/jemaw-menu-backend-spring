package com.agafari.com.plan;

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
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M4 (AC1–AC5, AC7, AC9–AC11): owners see the plans the current configuration enables (no prices) and request one
 * for their own restaurant. A request never creates or changes a subscription or entitlements. Hidden unless the owner
 * visibility flag (default off) is on.
 */
class PlanRequestOwnerApiTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanRequestService planRequests;
    @Autowired private EntitlementService entitlements;
    @Autowired private Environment environment;

    private User admin;
    private Tenant owner;

    @BeforeEach
    void setUp() {
        admin = data.admin("owner-plans");
        owner = data.tenant("plan-owner");
    }

    @AfterEach
    void hide() {
        planRequests.setOwnerPlansVisible(false);
        entitlements.setEnabled(false);
    }

    private ResultActions view() throws Exception {
        return mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(owner)));
    }

    private ResultActions request(String plan, int configVersion) throws Exception {
        return mockMvc.perform(post("/api/plan/requests").header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"planCode\":\"" + plan + "\",\"expectedConfigVersion\":" + configVersion + "}"));
    }

    private int requests() {
        return jdbc.queryForObject("select count(*) from plan_request where business_id = ?", Integer.class, owner.businessId());
    }

    private List<String> audit() {
        return jdbc.queryForList("select action || '/' || outcome || '/' || coalesce(detail, '') from admin_audit_event "
                + "where action like '%PLAN_REQUEST%' order by occurred_at", String.class);
    }

    @Test
    void ac1WithTheVisibilityFlagOffOwnersSeeNothingAndCanNotRequest() throws Exception {
        assertThat(environment.getProperty("app.monetization.owner-plans-visible", Boolean.class, false)).isFalse();
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        view().andExpect(status().isOk()).andExpect(jsonPath("$.visible").value(false)).andExpect(jsonPath("$.plans").doesNotExist());
        request("MODEL_2", 1).andExpect(status().isForbidden());
        assertThat(requests()).isZero();
    }

    @Test
    void ac1NotConfiguredMeansNothingToSeeAndNothingToRequest() throws Exception {
        planRequests.setOwnerPlansVisible(true);
        view().andExpect(jsonPath("$.visible").value(false));
        request("MODEL_2", 0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MONETIZATION_NOT_CONFIGURED"));
    }

    @Test
    void ac2UnderSetting2TheOwnerSeesExactlyTheEnabledPlansWithoutPrices() throws Exception {
        planRequests.setOwnerPlansVisible(true);
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        String body = view().andExpect(status().isOk())
                .andExpect(jsonPath("$.visible").value(true))
                .andExpect(jsonPath("$.setting").value("SETTING_2"))
                .andExpect(jsonPath("$.configVersion").value(1))
                .andExpect(jsonPath("$.plans.length()").value(2))
                .andExpect(jsonPath("$.plans[0].code").value("MODEL_2")).andExpect(jsonPath("$.plans[0].adSource").value("AGAFARI"))
                .andExpect(jsonPath("$.plans[1].code").value("MODEL_4")).andExpect(jsonPath("$.plans[1].paid").value(true))
                .andExpect(jsonPath("$.canRequest").value(true))
                .andExpect(jsonPath("$.featuresUnaffected").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(body.toLowerCase()).doesNotContain("price").doesNotContain("amount").doesNotContain("currency");
    }

    @Test
    void ac3UnderSetting1TheOwnerSeesStatusOnlyAndCanNotRequest() throws Exception {
        planRequests.setOwnerPlansVisible(true);
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        view().andExpect(jsonPath("$.setting").value("SETTING_1"))
                .andExpect(jsonPath("$.plans.length()").value(0))
                .andExpect(jsonPath("$.canRequest").value(false))
                .andExpect(jsonPath("$.trialAvailable").value(false));
        request("MODEL_1", 1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("planCode"));
        assertThat(requests()).isZero();
    }

    @Test
    void ac4ARequestStoresThePlanAndConfigurationVersionAndChangesNothingElse() throws Exception {
        planRequests.setOwnerPlansVisible(true);
        entitlements.setEnabled(true);
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        Entitlements before = entitlements.forBusiness(owner.businessId());

        request("MODEL_4", 1).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.planCode").value("MODEL_4"))
                .andExpect(jsonPath("$.rowVersion").isNumber());

        assertThat(jdbc.queryForMap("select plan_code, config_version, status, requested_by_user_id from plan_request where business_id = ?",
                owner.businessId())).containsEntry("plan_code", "MODEL_4").containsEntry("config_version", 1).containsEntry("status", "PENDING")
                .containsEntry("requested_by_user_id", owner.owner().getId());
        assertThat(jdbc.queryForObject("select count(*) from business_subscription", Integer.class)).isZero();
        assertThat(entitlements.forBusiness(owner.businessId())).isEqualTo(before);
        view().andExpect(jsonPath("$.pendingRequest.planCode").value("MODEL_4")).andExpect(jsonPath("$.canRequest").value(false));
        assertThat(audit()).singleElement().asString().startsWith("OWNER_PLAN_REQUESTED/SUCCESS/MODEL_4");
        assertThat(jdbc.queryForObject("select actor_label from admin_audit_event where action = 'OWNER_PLAN_REQUESTED'", String.class))
                .contains("***").doesNotContain(owner.owner().getEmail());
    }

    @Test
    void ac5RefusedRequestsAreAuditedAndCreateNothing() throws Exception {
        planRequests.setOwnerPlansVisible(true);
        config(jdbc, 1, "SETTING_2", true, false, false, admin.getId());
        request("MODEL_4", 1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("planCode")); // not enabled
        request("MODEL_9", 1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("planCode"));
        request("MODEL_2", 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIG_VERSION_CONFLICT"));
        mockMvc.perform(post("/api/plan/requests").header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"planCode\":\"MODEL_2\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors[0].field").value("expectedConfigVersion"));
        assertThat(requests()).isZero();

        request("MODEL_2", 1).andExpect(status().isCreated());
        request("MODEL_2", 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_REQUEST_EXISTS"));
        assertThat(requests()).isEqualTo(1);
        assertThat(audit()).filteredOn(a -> a.startsWith("OWNER_PLAN_REQUEST_REJECTED/FAILURE/")).hasSize(5);
    }

    @Test
    void ac5ARestaurantWithACurrentSubscriptionCanNotRequestAndSeesItsPlan() throws Exception {
        planRequests.setOwnerPlansVisible(true);
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        subscription(jdbc, owner.businessId(), "MODEL_4", 1, admin.getId());

        String body = view().andExpect(jsonPath("$.current.planCode").value("MODEL_4"))
                .andExpect(jsonPath("$.current.status").value("ACTIVE"))
                .andExpect(jsonPath("$.current.sourceLabel").value("Manual record, no payment verified"))
                .andExpect(jsonPath("$.current.createdBy").doesNotExist())
                .andExpect(jsonPath("$.canRequest").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(admin.getEmail()).doesNotContain(admin.getId());
        request("MODEL_2", 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_EXISTS"));
        assertThat(requests()).isZero();
    }

    @Test
    void ac7TheOwnerCanWithdrawAPendingRequestAndTheHistoryIsKept() throws Exception {
        planRequests.setOwnerPlansVisible(true);
        config(jdbc, 1, "SETTING_2", true, false, false, admin.getId());
        int rowVersion = JsonPath.read(request("MODEL_2", 1).andReturn().getResponse().getContentAsString(), "$.rowVersion");

        withdraw(rowVersion + 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_REQUEST_VERSION_CONFLICT"));
        withdraw(rowVersion).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WITHDRAWN"));
        withdraw(rowVersion).andExpect(status().isNotFound());

        assertThat(jdbc.queryForMap("select status, resolved_by_user_id from plan_request where business_id = ?", owner.businessId()))
                .containsEntry("status", "WITHDRAWN").containsEntry("resolved_by_user_id", owner.owner().getId());
        request("MODEL_2", 1).andExpect(status().isCreated()); // a new request is a new row
        assertThat(requests()).isEqualTo(2);
        assertThat(audit()).anyMatch(a -> a.startsWith("OWNER_PLAN_REQUEST_WITHDRAWN/SUCCESS/"));
    }

    private ResultActions withdraw(int rowVersion) throws Exception {
        return mockMvc.perform(patch("/api/plan/requests/current").header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"WITHDRAWN\",\"rowVersion\":" + rowVersion + "}"));
    }

    @Test
    void ac10OwnersOnlyEverActOnTheirOwnRestaurantAndRolesStaySeparate() throws Exception {
        planRequests.setOwnerPlansVisible(true);
        config(jdbc, 1, "SETTING_2", true, false, false, admin.getId());
        Tenant other = data.tenant("plan-other");
        request("MODEL_2", 1).andExpect(status().isCreated());

        mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(other))).andExpect(jsonPath("$.pendingRequest").doesNotExist());
        assertThat(jdbc.queryForList("select business_id from plan_request", String.class)).containsExactly(owner.businessId());

        String adminBearer = data.adminBearer(admin);
        mockMvc.perform(get("/api/plan").header("Authorization", adminBearer)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/plan")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/plan-requests").header("Authorization", data.bearer(owner))).andExpect(status().isForbidden());
    }

    @Test
    void ac9OwnerActionsAndConfigurationChangesNeverTouchSubscriptionsOrRequests() throws Exception {
        planRequests.setOwnerPlansVisible(true);
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        Tenant subscribed = data.tenant("plan-subscribed");
        subscription(jdbc, subscribed.businessId(), "MODEL_2", 1, admin.getId());
        Map<String, Object> subscriptionBefore = jdbc.queryForMap("select * from business_subscription");
        request("MODEL_4", 1).andExpect(status().isCreated());
        Map<String, Object> requestBefore = jdbc.queryForMap("select * from plan_request");

        config(jdbc, 2, "SETTING_1", false, false, false, admin.getId());

        assertThat(jdbc.queryForMap("select * from business_subscription")).isEqualTo(subscriptionBefore);
        assertThat(jdbc.queryForMap("select * from plan_request")).isEqualTo(requestBefore);
        view().andExpect(jsonPath("$.pendingRequest.outsideCurrentConfiguration").value(true));
    }
}
