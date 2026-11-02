package com.agafari.com.trial;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.PlanRequestService;
import com.agafari.com.service.TrialService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.pendingRequest;
import static com.agafari.com.support.MonetizationFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M5 (AC7, AC7a, AC8, AC9a; D-M5-6…8, D-M5-13): the Super Admin extends (only while running), ends, converts
 * (one transaction: trial ended and kept, new MANUAL Model 1 record) and manually starts trials. A running trial blocks a
 * manual record and an owner request; an effectively ended trial is expired on touch first, then treated as ended.
 */
class TrialAdminTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TrialService trials;
    @Autowired private PlanRequestService planRequests;
    @Autowired private MonetizationClock clock;

    private User admin;
    private String bearer;
    private Tenant t;

    @BeforeEach
    void setUp() {
        admin = data.admin("trial-admin");
        bearer = data.adminBearer(admin);
        t = data.tenant("trial-cafe");
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        trials.setEnabled(true);
    }

    @AfterEach
    void reset() {
        trials.setEnabled(false);
        planRequests.setOwnerPlansVisible(false);
        clock.reset();
    }

    private String trial(int daysAgoStarted) {
        String id = UUID.randomUUID().toString();
        Instant start = Instant.now().minus(Duration.ofDays(daysAgoStarted));
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, "
                + "created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_1', 'TRIAL', 1, ?, 'TRIAL', ?, 'system', ?, 'system', 0)",
                id, t.businessId(), Timestamp.from(start.plus(Duration.ofDays(30))), Timestamp.from(start), Timestamp.from(start));
        return id;
    }

    private ResultActions convert(int configVersion, int rowVersion) throws Exception {
        return mockMvc.perform(post("/api/admin/businesses/{id}/trial/convert", t.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedConfigVersion\":" + configVersion + ",\"rowVersion\":" + rowVersion + "}"));
    }

    private ResultActions start(int configVersion) throws Exception {
        return mockMvc.perform(post("/api/admin/businesses/{id}/trial", t.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedConfigVersion\":" + configVersion + "}"));
    }

    private ResultActions patchSubscription(String body) throws Exception {
        return mockMvc.perform(patch("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private List<String> audit() {
        return jdbc.queryForList("select action || '/' || outcome || '/' || coalesce(detail, '') from admin_audit_event "
                + "where action like 'TRIAL%' or action like 'SUBSCRIPTION%' order by occurred_at", String.class);
    }

    @Test
    void ac7ExtendWhileRunningAndEndAreAuditedAsTrialActions() throws Exception {
        trial(10);
        String newEnd = Instant.now().plus(40, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        patchSubscription("{\"periodEnd\":\"" + newEnd + "\",\"rowVersion\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.periodEnd").value(newEnd));
        patchSubscription("{\"status\":\"EXPIRED\",\"rowVersion\":1}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"));
        assertThat(audit()).anyMatch(a -> a.startsWith("TRIAL_EXTENDED/SUCCESS/")).anyMatch(a -> a.startsWith("TRIAL_ENDED/SUCCESS/"));
    }

    @Test
    void ac9aExtendingAnEffectivelyEndedTrialIsRefusedAndPersistsTheExpiryOnce() throws Exception {
        String id = trial(31); // ended a day ago, job not run
        String newEnd = Instant.now().plus(40, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        patchSubscription("{\"periodEnd\":\"" + newEnd + "\",\"rowVersion\":0}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TRIAL_ENDED"));
        assertThat(jdbc.queryForMap("select status, ended_at = period_end as at_end from business_subscription where id = ?", id))
                .containsEntry("status", "EXPIRED").containsEntry("at_end", true);
        assertThat(audit()).filteredOn(a -> a.startsWith("TRIAL_EXPIRED/")).singleElement().asString().contains("on_touch");
        assertThat(trials.runExpiry()).isZero(); // nothing left for the job
    }

    @Test
    void ac7aConvertEndsAndKeepsTheTrialAndRecordsAManualModel1Subscription() throws Exception {
        String trialId = trial(10);
        convert(1, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value("MODEL_1")).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.source").value("MANUAL")).andExpect(jsonPath("$.paymentVerified").value(false))
                .andExpect(jsonPath("$.sourceLabel").value("Manual record, no payment verified")).andExpect(jsonPath("$.configVersion").value(1));

        Map<String, Object> ended = jdbc.queryForMap("select status, source, plan_code, config_version, ended_at is not null as ended, "
                + "ended_at < period_end as early from business_subscription where id = ?", trialId);
        assertThat(ended).containsEntry("status", "EXPIRED").containsEntry("source", "TRIAL").containsEntry("plan_code", "MODEL_1")
                .containsEntry("config_version", 1).containsEntry("ended", true).containsEntry("early", true);
        assertThat(jdbc.queryForList("select source || ':' || status from business_subscription where business_id = ? order by created_at", String.class, t.businessId()))
                .containsExactly("TRIAL:EXPIRED", "MANUAL:ACTIVE");
        assertThat(audit()).anyMatch(a -> a.startsWith("TRIAL_CONVERTED/SUCCESS/")).anyMatch(a -> a.startsWith("SUBSCRIPTION_CREATED/SUCCESS/MODEL_1"));
        mockMvc.perform(get("/api/admin/businesses/{id}/subscriptions", t.businessId()).header("Authorization", bearer))
                .andExpect(jsonPath("$[1].sourceLabel").value("Trial (automatic), no payment involved"));
    }

    @Test
    void ac7aConvertingAnEffectivelyEndedTrialKeepsItsRealEndDate() throws Exception {
        String trialId = trial(31);
        convert(1, 0).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select ended_at = period_end from business_subscription where id = ?", Boolean.class, trialId)).isTrue();
    }

    @Test
    void ac7aConvertIsRefusedWithoutSetting1OrWithStaleVersions() throws Exception {
        String trialId = trial(10);
        convert(1, 5).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_VERSION_CONFLICT"));
        convert(0, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIG_VERSION_CONFLICT"));
        config(jdbc, 2, "SETTING_2", true, false, false, admin.getId());
        convert(2, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_NOT_ENABLED"));
        assertThat(jdbc.queryForObject("select status from business_subscription where id = ?", String.class, trialId)).isEqualTo("TRIAL");
        assertThat(jdbc.queryForObject("select count(*) from business_subscription", Integer.class)).isEqualTo(1);

        Tenant manual = data.tenant("no-trial-to-convert");
        subscription(jdbc, manual.businessId(), "MODEL_2", 2, admin.getId());
        mockMvc.perform(post("/api/admin/businesses/{id}/trial/convert", manual.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedConfigVersion\":2,\"rowVersion\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_VERSION_CONFLICT"));
    }

    @Test
    void ac7ManualStartFollowsTheEligibilityRules() throws Exception {
        trials.setEnabled(false);
        start(1).andExpect(status().isForbidden());
        trials.setEnabled(true);
        start(0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIG_VERSION_CONFLICT"));
        start(1).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("TRIAL")).andExpect(jsonPath("$.source").value("TRIAL"));
        assertThat(jdbc.queryForObject("select created_by_user_id from business_subscription where business_id = ?", String.class, t.businessId()))
                .isEqualTo(admin.getId());
        start(1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_EXISTS"));
        patchSubscription("{\"status\":\"EXPIRED\",\"rowVersion\":0}").andExpect(status().isOk());
        start(1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TRIAL_ALREADY_USED"));

        Tenant pending = data.tenant("pending-no-trial");
        config(jdbc, 2, "SETTING_2", true, false, false, admin.getId());
        pendingRequest(jdbc, pending.businessId(), "MODEL_2", 2, pending.owner().getId());
        config(jdbc, 3, "SETTING_1", false, false, false, admin.getId());
        mockMvc.perform(post("/api/admin/businesses/{id}/trial", pending.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedConfigVersion\":3}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLAN_REQUEST_PENDING"));
        mockMvc.perform(post("/api/admin/businesses/{id}/trial", data.tenant("under-setting2").businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedConfigVersion\":2}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIG_VERSION_CONFLICT"));
    }

    @Test
    void ac8ARunningTrialBlocksAManualRecordButAnEffectivelyEndedOneIsExpiredOnTouch() throws Exception {
        String id = trial(10);
        mockMvc.perform(post("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"planCode\":\"MODEL_1\",\"expectedConfigVersion\":1}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_EXISTS"));

        clock.setOffset(Duration.ofDays(21));
        mockMvc.perform(post("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"planCode\":\"MODEL_1\",\"expectedConfigVersion\":1}"))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("select status from business_subscription where id = ?", String.class, id)).isEqualTo("EXPIRED");
        assertThat(audit()).filteredOn(a -> a.startsWith("TRIAL_EXPIRED/")).singleElement().asString().contains("on_touch");
    }

    @Test
    void ac8AnOwnerRequestUnderSetting2IsBlockedByARunningTrialAndWorksAfterItEnded() throws Exception {
        planRequests.setOwnerPlansVisible(true);
        trial(10);
        config(jdbc, 2, "SETTING_2", true, false, false, admin.getId());
        String body = "{\"planCode\":\"MODEL_2\",\"expectedConfigVersion\":2}";
        mockMvc.perform(post("/api/plan/requests").header("Authorization", data.bearer(t)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_EXISTS"));
        mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(t)))
                .andExpect(jsonPath("$.trial.effectiveStatus").value("TRIAL")).andExpect(jsonPath("$.canRequest").value(false));

        clock.setOffset(Duration.ofDays(21));
        mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(t)))
                .andExpect(jsonPath("$.trial.effectiveStatus").value("EXPIRED")).andExpect(jsonPath("$.canRequest").value(true));
        mockMvc.perform(post("/api/plan/requests").header("Authorization", data.bearer(t)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void ac8AConfigurationChangeNeverEndsOrModifiesARunningTrial() throws Exception {
        String id = trial(10);
        Map<String, Object> before = jdbc.queryForMap("select * from business_subscription where id = ?", id);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/monetization-config")
                .header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"setting\":\"SETTING_2\",\"models\":[\"MODEL_4\"],\"expectedVersion\":1}")).andExpect(status().isOk());
        assertThat(jdbc.queryForMap("select * from business_subscription where id = ?", id)).isEqualTo(before);
        mockMvc.perform(get("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", bearer))
                .andExpect(jsonPath("$.current.outsideCurrentConfiguration").value(true))
                .andExpect(jsonPath("$.current.effectiveStatus").value("TRIAL"));
    }
}
