package com.agafari.com.grandfathering;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.EntitlementService;
import com.agafari.com.service.GrandfatheringService;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.PlanRequestService;
import com.agafari.com.service.TrialService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.agafari.com.grandfathering.GrandfatheringFixtures.manual;
import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M6 (AC7–AC10, AC12, Q2, Q9, Q10, D-M6-3): with {@code transitions-enabled} off (the default) grandfathering is
 * informational only. Nothing ends, nothing is effectively ended, eligibility and entitlements are unchanged. With it on,
 * reads show the end before the job, writes expire on touch, and the existing job ends due grandfathered subscriptions at
 * their approved instant, once, catching up overdue ones. Other manual subscriptions never end.
 */
class GrandfatheringFlagTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private GrandfatheringService grandfathering;
    @Autowired private EntitlementService entitlements;
    @Autowired private PlanRequestService planRequests;
    @Autowired private TrialService trials;
    @Autowired private MonetizationClock clock;
    @Autowired private DataSource dataSource;
    @Autowired private Environment environment;

    private User admin;
    private String bearer;

    @BeforeEach
    void setUp() {
        admin = data.admin("grandfathering-flag");
        bearer = data.adminBearer(admin);
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId());
    }

    @AfterEach
    void reset() {
        grandfathering.setEnabled(false);
        entitlements.setEnabled(false);
        planRequests.setOwnerPlansVisible(false);
        trials.setEnabled(false);
        clock.reset();
    }

    /** A Model 3 subscription that started 60 days ago and ended (period end) {@code daysAgo} days ago; then Model 3 is disabled. */
    private String overdue(Tenant t, int daysAgo) {
        return manual(jdbc, t.businessId(), "MODEL_3", 1, Instant.now().minus(Duration.ofDays(daysAgo)), Instant.now().minus(Duration.ofDays(60)), admin.getId());
    }

    private void disableModel3() {
        config(jdbc, 2, "SETTING_2", true, false, true, admin.getId());
    }

    private ResultActions view(Tenant t) throws Exception {
        return mockMvc.perform(get("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", bearer));
    }

    private ResultActions ownerRequest(Tenant t) throws Exception {
        return mockMvc.perform(post("/api/plan/requests").header("Authorization", data.bearer(t)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"planCode\":\"MODEL_4\",\"expectedConfigVersion\":2}"));
    }

    private String statusOf(String id) {
        return jdbc.queryForObject("select status from business_subscription where id = ?", String.class, id);
    }

    private int ended() {
        return jdbc.queryForObject("select count(*) from admin_audit_event where action = 'GRANDFATHERING_ENDED'", Integer.class);
    }

    @Test
    void ac7WithTheFlagOffByDefaultGrandfatheringIsInformationalOnly() throws Exception {
        assertThat(environment.getProperty("app.monetization.transitions-enabled", Boolean.class, true)).isFalse();
        entitlements.setEnabled(true);
        planRequests.setOwnerPlansVisible(true);
        Tenant t = data.tenant("gf-off");
        String id = overdue(t, 5);
        disableModel3();
        Map<String, String> before = hashes();

        view(t).andExpect(jsonPath("$.current.status").value("ACTIVE"))
                .andExpect(jsonPath("$.current.effectiveStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.current.effectiveEnd").doesNotExist())
                .andExpect(jsonPath("$.current.grandfathering.overdue").value(true))
                .andExpect(jsonPath("$.current.grandfathering.transitionsEnabled").value(false))
                .andExpect(jsonPath("$.entitlements.planCode").value("MODEL_3"));
        mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(t)))
                .andExpect(jsonPath("$.current.planCode").value("MODEL_3"))
                .andExpect(jsonPath("$.current.grandfathering.transitionsEnabled").value(false))
                .andExpect(jsonPath("$.canRequest").value(false))
                .andExpect(jsonPath("$.endedPlan").doesNotExist());
        assertThat(hashes()).isEqualTo(before); // GETs wrote nothing

        assertThat(grandfathering.runEnd()).isZero();
        ownerRequest(t).andExpect(status().isConflict()); // the current subscription still counts
        mockMvc.perform(post("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"planCode\":\"MODEL_4\",\"expectedConfigVersion\":2}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_EXISTS"));
        assertThat(statusOf(id)).isEqualTo("ACTIVE");
        assertThat(ended()).isZero();
    }

    @Test
    void ac10WithTheFlagOnReadsShowTheEndBeforeTheJobAndAWriteExpiresItOnTouchOnce() throws Exception {
        grandfathering.setEnabled(true);
        entitlements.setEnabled(true);
        planRequests.setOwnerPlansVisible(true);
        Tenant t = data.tenant("gf-on-touch");
        String id = overdue(t, 5);
        Instant periodEnd = jdbc.queryForObject("select period_end from business_subscription where id = ?", Timestamp.class, id).toInstant();
        disableModel3();
        Map<String, String> before = hashes();

        view(t).andExpect(jsonPath("$.current.status").value("ACTIVE"))
                .andExpect(jsonPath("$.current.effectiveStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.current.effectiveEnd").value(periodEnd.toString()))
                .andExpect(jsonPath("$.entitlements.planCode").doesNotExist());
        mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(t)))
                .andExpect(jsonPath("$.current").doesNotExist())
                .andExpect(jsonPath("$.endedPlan.planCode").value("MODEL_3"))
                .andExpect(jsonPath("$.endedPlan.endedAt").value(periodEnd.toString()))
                .andExpect(jsonPath("$.canRequest").value(true));
        assertThat(hashes()).isEqualTo(before);

        ownerRequest(t).andExpect(status().isCreated()); // expire on touch, then the M4 request
        assertThat(statusOf(id)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select ended_at = period_end from business_subscription where id = ?", Boolean.class, id)).isTrue();
        assertThat(ended()).isEqualTo(1);
        assertThat(grandfathering.runEnd()).isZero();
        assertThat(ended()).isEqualTo(1);
    }

    @Test
    void ac8TheJobEndsOnlyDueGrandfatheredSubscriptionsAtTheirApprovedInstantOnce() throws Exception {
        grandfathering.setEnabled(true);
        Tenant due = data.tenant("gf-due");
        String dueId = overdue(due, 3);
        Tenant notDue = data.tenant("gf-not-due");
        String notDueId = manual(jdbc, notDue.businessId(), "MODEL_3", 1, Instant.now().plus(Duration.ofDays(5)), Instant.now().minus(Duration.ofDays(60)), admin.getId());
        Tenant enabled = data.tenant("gf-still-enabled");
        String enabledId = manual(jdbc, enabled.businessId(), "MODEL_4", 1, Instant.now().minus(Duration.ofDays(3)), Instant.now().minus(Duration.ofDays(60)), admin.getId());
        Tenant open = data.tenant("gf-open-no-date");
        String openId = manual(jdbc, open.businessId(), "MODEL_3", 1, null, Instant.now().minus(Duration.ofDays(60)), admin.getId());
        disableModel3();

        assertThat(grandfathering.runEnd()).isEqualTo(1);
        assertThat(statusOf(dueId)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("select ended_at = period_end from business_subscription where id = ?", Boolean.class, dueId)).isTrue(); // D-M6-3
        assertThat(statusOf(notDueId)).isEqualTo("ACTIVE");
        assertThat(statusOf(enabledId)).isEqualTo("ACTIVE"); // Q9: other manual subscriptions never end
        assertThat(statusOf(openId)).isEqualTo("ACTIVE"); // Q3
        String detail = jdbc.queryForObject("select detail from admin_audit_event where action = 'GRANDFATHERING_ENDED'", String.class);
        assertThat(detail).contains("MODEL_3").contains("source=period_end").contains("config=1").contains("current=2")
                .contains("processedAt=").contains("processed_late").contains("via=job");
        assertThat(grandfathering.runEnd()).isZero(); // idempotent

        clock.setOffset(Duration.ofDays(6)); // the not-due one becomes due
        assertThat(grandfathering.runEnd()).isEqualTo(1);
        assertThat(statusOf(notDueId)).isEqualTo("EXPIRED");
        assertThat(ended()).isEqualTo(2);
    }

    @Test
    void ac8AnOpenEndedSubscriptionEndsAtItsValidTransitionDateAndTrialsAreLeftToTheTrialJob() throws Exception {
        grandfathering.setEnabled(true);
        Tenant open = data.tenant("gf-open-dated");
        jdbc.update("update business set time_zone = 'UTC' where id = ?", open.businessId());
        String openId = manual(jdbc, open.businessId(), "MODEL_3", 1, null, Instant.now().minus(Duration.ofDays(60)), admin.getId());
        Tenant trial = data.tenant("gf-trial-untouched");
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, "
                + "created_by_user_id, updated_at, updated_by_user_id, row_version) values (gen_random_uuid()::text, ?, 'MODEL_1', 'TRIAL', 1, "
                + "now() - interval '1 day', 'TRIAL', now() - interval '31 days', 'system', now(), 'system', 0)", trial.businessId());
        disableModel3();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/businesses/{id}/subscription/transition-date",
                open.businessId()).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"transitionDate\":\"" + java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(2) + "\",\"rowVersion\":0}")).andExpect(status().isOk());
        Instant transition = jdbc.queryForObject("select transition_date from business_subscription where id = ?", Timestamp.class, openId).toInstant();

        assertThat(grandfathering.runEnd()).isZero();
        clock.setOffset(Duration.between(Instant.now(), transition.plusSeconds(1)));
        assertThat(grandfathering.runEnd()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select ended_at from business_subscription where id = ?", Timestamp.class, openId).toInstant()).isEqualTo(transition);
        assertThat(jdbc.queryForObject("select detail from admin_audit_event where action = 'GRANDFATHERING_ENDED'", String.class)).contains("source=transition_date");
        assertThat(jdbc.queryForObject("select status from business_subscription where business_id = ?", String.class, trial.businessId())).isEqualTo("TRIAL");
    }

    @Test
    void ac9TurningTheFlagOnLaterEndsExactlyTheOverdueBacklogOnce() throws Exception {
        Tenant a = data.tenant("gf-backlog-a");
        String aId = overdue(a, 20);
        Tenant b = data.tenant("gf-backlog-b");
        String bId = overdue(b, 2);
        Tenant future = data.tenant("gf-backlog-future");
        String futureId = manual(jdbc, future.businessId(), "MODEL_3", 1, Instant.now().plus(Duration.ofDays(9)), Instant.now().minus(Duration.ofDays(60)), admin.getId());
        disableModel3();
        assertThat(grandfathering.runEnd()).isZero(); // off: nothing

        grandfathering.setEnabled(true);
        trials.setEnabled(false); // independent of trials
        assertThat(grandfathering.runEnd()).isEqualTo(2);
        assertThat(statusOf(aId)).isEqualTo("EXPIRED");
        assertThat(statusOf(bId)).isEqualTo("EXPIRED");
        assertThat(statusOf(futureId)).isEqualTo("ACTIVE");
        assertThat(grandfathering.runEnd()).isZero();
        assertThat(ended()).isEqualTo(2);
    }

    @Test
    void ac8ASecondInstanceSkipsWhileOneRuns() throws Exception {
        grandfathering.setEnabled(true);
        Tenant t = data.tenant("gf-locked");
        overdue(t, 3);
        disableModel3();
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (Statement s = other.createStatement()) {
                s.execute("select pg_advisory_xact_lock(" + TrialService.TRIAL_JOB_LOCK_KEY + ")");
            }
            assertThat(grandfathering.runEnd()).isEqualTo(-1);
            other.rollback();
        }
        assertThat(grandfathering.runEnd()).isEqualTo(1);
    }

    @Test
    void ac12AfterTheEndUnderSetting1TheOwnerSeesTheEndedPlanAndCannotSelfRequest() throws Exception {
        grandfathering.setEnabled(true);
        planRequests.setOwnerPlansVisible(true);
        Tenant t = data.tenant("gf-setting1");
        jdbc.update("update business set time_zone = 'America/Chicago' where id = ?", t.businessId());
        overdue(t, 2);
        config(jdbc, 2, "SETTING_1", false, false, false, admin.getId());
        assertThat(grandfathering.runEnd()).isEqualTo(1);
        mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(t)))
                .andExpect(jsonPath("$.setting").value("SETTING_1"))
                .andExpect(jsonPath("$.endedPlan.planCode").value("MODEL_3"))
                .andExpect(jsonPath("$.endedPlan.timeZone").value("America/Chicago"))
                .andExpect(jsonPath("$.canRequest").value(false));
    }

    private Map<String, String> hashes() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : jdbc.queryForList("select table_name from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE' "
                + "and table_name <> 'menu_view_daily' order by 1", String.class)) {
            result.put(table, jdbc.queryForObject("select coalesce(md5(string_agg(t::text, '|' order by t::text)), '-') from \"" + table + "\" t", String.class));
        }
        return result;
    }
}
