package com.agafari.com.grandfathering;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.service.GrandfatheringService;
import com.agafari.com.service.PlanRequestService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static com.agafari.com.grandfathering.GrandfatheringFixtures.manual;
import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M6 deviation 1 (PO verification, real PostgreSQL): the Super Admin extends or manually ends a grandfathered
 * subscription that is already past its approved end. With transitions on, expire-on-touch commits the M6 end in its own
 * transaction before the request's own transaction, which then finds no current subscription (404); a failure inside the
 * expire-on-touch rolls it back completely (still ACTIVE, still effectively ended on reads, ended later by the job). With
 * transitions off nothing is ended: the M3 actions behave exactly as before.
 */
class GrandfatheringOverdueAdminActionTest extends AbstractIntegrationTest {

    @MockitoSpyBean private AdminAuditService audit;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private GrandfatheringService grandfathering;
    @Autowired private PlanRequestService planRequests;

    private User admin;
    private String bearer;
    private Tenant t;
    private String id;
    private Instant periodEnd;

    @BeforeEach
    void setUp() {
        admin = data.admin("gf-overdue-admin");
        bearer = data.adminBearer(admin);
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
        t = data.tenant("gf-overdue-admin");
        id = manual(jdbc, t.businessId(), "MODEL_3", 1, Instant.now().minus(Duration.ofDays(3)), Instant.now().minus(Duration.ofDays(60)), admin.getId());
        periodEnd = jdbc.queryForObject("select period_end from business_subscription where id = ?", Timestamp.class, id).toInstant();
        config(jdbc, 2, "SETTING_2", true, false, true, admin.getId()); // Model 3 disabled: grandfathered, 3 days overdue
    }

    @AfterEach
    void restore() {
        grandfathering.setEnabled(false);
        planRequests.setOwnerPlansVisible(false);
        reset(audit);
    }

    private ResultActions patchSubscription(String body) throws Exception {
        return mockMvc.perform(patch("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private Map<String, Object> row() {
        return jdbc.queryForMap("select status, ended_at, period_end, row_version from business_subscription where id = ?", id);
    }

    private int audits(String action) {
        return jdbc.queryForObject("select count(*) from admin_audit_event where action = ?", Integer.class, action);
    }

    private void assertEndedByM6AndConsistent() throws Exception {
        Map<String, Object> r = row();
        assertThat(r.get("status")).isEqualTo("EXPIRED");
        assertThat(((Timestamp) r.get("ended_at")).toInstant()).isEqualTo(periodEnd); // the approved end instant (D-M6-3)
        assertThat(audits("GRANDFATHERING_ENDED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select detail from admin_audit_event where action = 'GRANDFATHERING_ENDED'", String.class)).contains("via=on_touch");
        assertThat(jdbc.queryForObject("select detail from admin_audit_event where action = 'SUBSCRIPTION_CHANGE_REJECTED'", String.class)).isEqualTo("not_found");
        assertThat(audits("SUBSCRIPTION_UPDATED")).isZero(); // the rejected request changed nothing itself
        mockMvc.perform(get("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", bearer))
                .andExpect(jsonPath("$.current").doesNotExist());
        mockMvc.perform(get("/api/admin/businesses/{id}/subscriptions", t.businessId()).header("Authorization", bearer))
                .andExpect(jsonPath("$[0].status").value("EXPIRED"))
                .andExpect(jsonPath("$[0].endedAt").value(periodEnd.toString()));
        mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(t)))
                .andExpect(jsonPath("$.current").doesNotExist())
                .andExpect(jsonPath("$.endedPlan.endedAt").value(periodEnd.toString()));
        assertThat(grandfathering.runEnd()).isZero(); // nothing left for the job
        assertThat(audits("GRANDFATHERING_ENDED")).isEqualTo(1);
    }

    @Test
    void flagOnExtendingAnOverdueGrandfatheredSubscriptionPersistsTheM6EndAndReturns404() throws Exception {
        grandfathering.setEnabled(true);
        planRequests.setOwnerPlansVisible(true);
        patchSubscription("{\"periodEnd\":\"" + Instant.now().plus(Duration.ofDays(30)) + "\",\"rowVersion\":0}")
                .andExpect(status().isNotFound());
        assertEndedByM6AndConsistent();
        // a retry sees the same, consistent state
        patchSubscription("{\"periodEnd\":\"" + Instant.now().plus(Duration.ofDays(30)) + "\",\"rowVersion\":1}").andExpect(status().isNotFound());
        assertThat(audits("GRANDFATHERING_ENDED")).isEqualTo(1);
    }

    @Test
    void flagOnManuallyEndingAnOverdueGrandfatheredSubscriptionPersistsTheM6EndAndReturns404() throws Exception {
        grandfathering.setEnabled(true);
        planRequests.setOwnerPlansVisible(true);
        patchSubscription("{\"status\":\"EXPIRED\",\"rowVersion\":0}").andExpect(status().isNotFound());
        assertEndedByM6AndConsistent();
    }

    @Test
    void flagOnAFailureInsideExpireOnTouchRollsItBackCompletelyAndTheJobEndsItLater() throws Exception {
        grandfathering.setEnabled(true);
        doThrow(new IllegalStateException("audit store unavailable")).when(audit)
                .success(eq(GrandfatheringService.ENDED), any(), any(), any(), any(), any());

        patchSubscription("{\"status\":\"EXPIRED\",\"rowVersion\":0}").andExpect(status().isInternalServerError());

        Map<String, Object> r = row();
        assertThat(r.get("status")).isEqualTo("ACTIVE"); // no half state: the end and its audit roll back together
        assertThat(r.get("ended_at")).isNull();
        assertThat(r.get("row_version")).isEqualTo(0);
        assertThat(audits("GRANDFATHERING_ENDED")).isZero();
        assertThat(audits("SUBSCRIPTION_UPDATED")).isZero(); // the manual end never ran
        mockMvc.perform(get("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", bearer))
                .andExpect(jsonPath("$.current.status").value("ACTIVE"))
                .andExpect(jsonPath("$.current.effectiveStatus").value("EXPIRED")) // reads stay consistent with the effective rule
                .andExpect(jsonPath("$.entitlements.planCode").doesNotExist());

        reset(audit);
        assertThat(grandfathering.runEnd()).isEqualTo(1);
        assertThat(row().get("status")).isEqualTo("EXPIRED");
        assertThat(((Timestamp) row().get("ended_at")).toInstant()).isEqualTo(periodEnd);
        assertThat(audits("GRANDFATHERING_ENDED")).isEqualTo(1);
    }

    @Test
    void flagOffTheM3ActionsAreUnchangedForAnOverdueGrandfatheredSubscription() throws Exception {
        Instant extended = Instant.now().plus(Duration.ofDays(30)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        patchSubscription("{\"periodEnd\":\"" + extended + "\",\"rowVersion\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.effectiveStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.grandfathering.until").value(extended.toString()))
                .andExpect(jsonPath("$.grandfathering.overdue").value(false));
        assertThat(audits("GRANDFATHERING_ENDED")).isZero();

        patchSubscription("{\"status\":\"EXPIRED\",\"rowVersion\":1}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"));
        Map<String, Object> r = row();
        assertThat(r.get("status")).isEqualTo("EXPIRED");
        assertThat(((Timestamp) r.get("ended_at")).toInstant()).isAfter(Instant.now().minus(Duration.ofMinutes(1))); // a manual end: now
        assertThat(audits("SUBSCRIPTION_UPDATED")).isEqualTo(2);
        assertThat(audits("GRANDFATHERING_ENDED")).isZero();
    }
}
