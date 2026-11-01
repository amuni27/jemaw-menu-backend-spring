package com.agafari.com.plan;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.Map;

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.pendingRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M4 (AC8, AC8a, AC6a, AC9, AC11): the Super Admin lists pending plan requests, fulfils one by recording the M3
 * manual subscription for the requested plan (atomically linked), or declines it. A direct manual record is refused while
 * a request is pending (I1). Fulfilment only against the current configuration and only for an enabled plan (I2).
 */
class PlanRequestAdminApiTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    private User admin;
    private String bearer;
    private Tenant owner;

    @BeforeEach
    void setUp() {
        admin = data.admin("plan-admin");
        bearer = data.adminBearer(admin);
        owner = data.tenant("requesting-cafe");
    }

    private ResultActions fulfil(String id, int configVersion, int rowVersion) throws Exception {
        return mockMvc.perform(post("/api/admin/plan-requests/{id}/fulfil", id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedConfigVersion\":" + configVersion + ",\"rowVersion\":" + rowVersion + "}"));
    }

    private List<String> audit() {
        return jdbc.queryForList("select action || '/' || outcome || '/' || coalesce(detail, '') from admin_audit_event "
                + "where action like 'PLAN_REQUEST%' or action like 'SUBSCRIPTION%' order by occurred_at", String.class);
    }

    @Test
    void thePendingListShowsRestaurantPlanVersionAndWhetherItIsOutsideTheConfiguration() throws Exception {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        pendingRequest(jdbc, owner.businessId(), "MODEL_4", 1, owner.owner().getId());
        mockMvc.perform(get("/api/admin/plan-requests").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].businessName").value(owner.business().getName()))
                .andExpect(jsonPath("$.items[0].planCode").value("MODEL_4"))
                .andExpect(jsonPath("$.items[0].configVersion").value(1))
                .andExpect(jsonPath("$.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.items[0].outsideCurrentConfiguration").value(false))
                .andExpect(jsonPath("$.items[0].requestedBy").value(org.hamcrest.Matchers.containsString("***")));
        config(jdbc, 2, "SETTING_2", true, false, false, admin.getId());
        mockMvc.perform(get("/api/admin/plan-requests").header("Authorization", bearer))
                .andExpect(jsonPath("$.items[0].outsideCurrentConfiguration").value(true));
        mockMvc.perform(get("/api/admin/businesses/{id}/subscription", owner.businessId()).header("Authorization", bearer))
                .andExpect(jsonPath("$.pendingRequest.planCode").value("MODEL_4"));
        mockMvc.perform(get("/api/admin/plan-requests").param("size", "101").header("Authorization", bearer)).andExpect(status().isBadRequest());
    }

    @Test
    void ac8FulfilRecordsTheManualSubscriptionForTheRequestedPlanAndLinksTheRequest() throws Exception {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        String id = pendingRequest(jdbc, owner.businessId(), "MODEL_4", 1, owner.owner().getId());

        fulfil(id, 1, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"))
                .andExpect(jsonPath("$.subscriptionId").isNotEmpty());

        Map<String, Object> sub = jdbc.queryForMap("select id, plan_code, status, source, config_version from business_subscription where business_id = ?",
                owner.businessId());
        assertThat(sub).containsEntry("plan_code", "MODEL_4").containsEntry("status", "ACTIVE").containsEntry("source", "MANUAL")
                .containsEntry("config_version", 1);
        assertThat(jdbc.queryForMap("select status, subscription_id, resolved_by_user_id from plan_request where id = ?", id))
                .containsEntry("status", "FULFILLED").containsEntry("subscription_id", sub.get("id")).containsEntry("resolved_by_user_id", admin.getId());
        assertThat(audit()).anyMatch(a -> a.startsWith("SUBSCRIPTION_CREATED/SUCCESS/MODEL_4"))
                .anyMatch(a -> a.startsWith("PLAN_REQUEST_FULFILLED/SUCCESS/MODEL_4"));
    }

    @Test
    void ac8DeclineEndsTheRequestWithoutASubscription() throws Exception {
        config(jdbc, 1, "SETTING_2", true, false, false, admin.getId());
        String id = pendingRequest(jdbc, owner.businessId(), "MODEL_2", 1, owner.owner().getId());
        mockMvc.perform(post("/api/admin/plan-requests/{id}/decline", id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVersion\":1}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_REQUEST_VERSION_CONFLICT"));
        mockMvc.perform(post("/api/admin/plan-requests/{id}/decline", id).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVersion\":0}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED"));
        assertThat(jdbc.queryForObject("select count(*) from business_subscription", Integer.class)).isZero();
        fulfil(id, 1, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_REQUEST_VERSION_CONFLICT")); // no longer pending
        mockMvc.perform(post("/api/admin/plan-requests/{id}/decline", "no-such-request").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rowVersion\":0}")).andExpect(status().isNotFound());
        assertThat(audit()).anyMatch(a -> a.startsWith("PLAN_REQUEST_DECLINED/SUCCESS/MODEL_2"));
    }

    @Test
    void ac8aFulfilChecksTheCurrentConfigurationAndKeepsTheRequestPendingOnRefusal() throws Exception {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        String id = pendingRequest(jdbc, owner.businessId(), "MODEL_4", 1, owner.owner().getId());
        config(jdbc, 2, "SETTING_2", true, false, false, admin.getId()); // Model 4 disabled

        fulfil(id, 1, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIG_VERSION_CONFLICT"));
        fulfil(id, 2, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_NOT_ENABLED"));
        fulfil(id, 2, 5).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_REQUEST_VERSION_CONFLICT"));

        assertThat(jdbc.queryForObject("select status from plan_request where id = ?", String.class, id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from business_subscription", Integer.class)).isZero();
        assertThat(audit()).filteredOn(a -> a.startsWith("PLAN_REQUEST_REJECTED/FAILURE/")).extracting(a -> a.substring(a.lastIndexOf('/') + 1))
                .containsExactly("version_conflict", "plan_not_enabled", "version_conflict");
    }

    @Test
    void ac6aADirectManualRecordIsRefusedWhileARequestIsPending() throws Exception {
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        pendingRequest(jdbc, owner.businessId(), "MODEL_4", 1, owner.owner().getId());
        mockMvc.perform(post("/api/admin/businesses/{id}/subscription", owner.businessId()).header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planCode\":\"MODEL_2\",\"expectedConfigVersion\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_REQUEST_PENDING"));
        assertThat(jdbc.queryForObject("select count(*) from business_subscription", Integer.class)).isZero();
        assertThat(audit()).anyMatch(a -> a.equals("SUBSCRIPTION_CHANGE_REJECTED/FAILURE/request_pending"));
    }

    @Test
    void onlySuperAdminsCanListFulfilOrDecline() throws Exception {
        config(jdbc, 1, "SETTING_2", true, false, false, admin.getId());
        String id = pendingRequest(jdbc, owner.businessId(), "MODEL_2", 1, owner.owner().getId());
        mockMvc.perform(get("/api/admin/plan-requests")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/plan-requests").header("Authorization", data.bearer(owner))).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/plan-requests/{id}/fulfil", id).header("Authorization", data.bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedConfigVersion\":1,\"rowVersion\":0}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/plan-requests/{id}/decline", id).header("Authorization", data.bearer(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"rowVersion\":0}")).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select status from plan_request where id = ?", String.class, id)).isEqualTo("PENDING");
    }
}
