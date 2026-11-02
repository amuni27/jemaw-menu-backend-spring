package com.agafari.com.grandfathering;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.GrandfatheringService;
import com.agafari.com.service.MonetizationClock;
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
import java.util.UUID;

import static com.agafari.com.grandfathering.GrandfatheringFixtures.manual;
import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M6 (AC2–AC6, AC11, AC11a, AC14, AC15, AC17): grandfathering is computed (Q1, Q5), the period end is authoritative
 * (Option B), open-ended subscriptions end only at an admin transition date (Q3) set with the restaurant-local calendar
 * rule, bound to the configuration version it was set under through the append-only audit record (D-M6-1). Q11 refuses
 * shortening a grandfathered period end; Q12 clears a transition date when a period end is set (D-M6-2). The M3 manual
 * End action is unchanged (§3.7).
 */
class GrandfatheringRulesTest extends AbstractIntegrationTest {

    private static final String FIELD = "$.fieldErrors[?(@.field == 'transitionDate')].message";
    private static final String PERIOD_FIELD = "$.fieldErrors[?(@.field == 'periodEnd')].message";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private GrandfatheringService grandfathering;
    @Autowired private MonetizationClock clock;

    private User admin;
    private String bearer;

    @BeforeEach
    void setUp() {
        admin = data.admin("grandfathering-rules");
        bearer = data.adminBearer(admin);
        config(jdbc, 1, "SETTING_2", true, true, true, admin.getId()); // Models 2, 3, 4
    }

    @AfterEach
    void reset() {
        grandfathering.setEnabled(false);
        clock.reset();
    }

    private void disableModel3(int version) {
        config(jdbc, version, "SETTING_2", true, false, true, admin.getId());
    }

    private void enableModel3(int version) {
        config(jdbc, version, "SETTING_2", true, true, true, admin.getId());
    }

    private Tenant restaurant(String label, String timeZone) {
        Tenant t = data.tenant(label);
        jdbc.update("update business set time_zone = ? where id = ?", timeZone, t.businessId());
        return t;
    }

    private ResultActions view(Tenant t) throws Exception {
        return mockMvc.perform(get("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", bearer));
    }

    private ResultActions setTransition(Tenant t, String body) throws Exception {
        return mockMvc.perform(put("/api/admin/businesses/{id}/subscription/transition-date", t.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions setTransitionTo(Tenant t, String date) throws Exception {
        return setTransition(t, "{\"transitionDate\":\"" + date + "\",\"rowVersion\":" + rowVersion(t) + "}");
    }

    private ResultActions clearTransition(Tenant t) throws Exception {
        return mockMvc.perform(post("/api/admin/businesses/{id}/subscription/transition-date/clear", t.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rowVersion\":" + rowVersion(t) + "}"));
    }

    private ResultActions patchSubscription(Tenant t, String body) throws Exception {
        return mockMvc.perform(patch("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private int rowVersion(Tenant t) {
        return jdbc.queryForObject("select row_version from business_subscription where business_id = ? and status <> 'EXPIRED'", Integer.class, t.businessId());
    }

    private Instant transitionDate(Tenant t) {
        Timestamp ts = jdbc.queryForObject("select transition_date from business_subscription where business_id = ? and status <> 'EXPIRED'", Timestamp.class, t.businessId());
        return ts == null ? null : ts.toInstant();
    }

    private int audits(String action) {
        return jdbc.queryForObject("select count(*) from admin_audit_event where action = ?", Integer.class, action);
    }

    @Test
    void ac2ASubscriptionWhosePlanIsNoLongerEnabledIsGrandfatheredUntilItsPeriodEndAndReenablingRemovesIt() throws Exception {
        Tenant t = data.tenant("gf-classified");
        Instant end = Instant.now().plus(Duration.ofDays(40));
        String id = manual(jdbc, t.businessId(), "MODEL_3", 1, end, Instant.now().minus(Duration.ofDays(1)), admin.getId());
        String before = jdbc.queryForObject("select row_to_json(s)::text from business_subscription s where id = ?", String.class, id);
        view(t).andExpect(jsonPath("$.current.grandfathering").doesNotExist());

        disableModel3(2);
        view(t).andExpect(jsonPath("$.current.status").value("ACTIVE"))
                .andExpect(jsonPath("$.current.outsideCurrentConfiguration").value(true))
                .andExpect(jsonPath("$.current.grandfathering.until").value(end.truncatedTo(java.time.temporal.ChronoUnit.MICROS).toString()))
                .andExpect(jsonPath("$.current.grandfathering.untilSource").value("PERIOD_END"))
                .andExpect(jsonPath("$.current.grandfathering.transitionsEnabled").value(false))
                .andExpect(jsonPath("$.current.grandfathering.overdue").value(false));

        enableModel3(3); // Q5
        view(t).andExpect(jsonPath("$.current.grandfathering").doesNotExist());
        assertThat(jdbc.queryForObject("select row_to_json(s)::text from business_subscription s where id = ?", String.class, id)).isEqualTo(before); // AC1/AC15
    }

    @Test
    void ac2OpenEndedSubscriptionsHaveNoEndAndTrialsAreNeverGrandfathered() throws Exception {
        Tenant open = data.tenant("gf-open");
        manual(jdbc, open.businessId(), "MODEL_3", 1, null, Instant.now().minus(Duration.ofDays(1)), admin.getId());
        Tenant trial = data.tenant("gf-trial");
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, "
                + "created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_1', 'TRIAL', 1, now() + interval '20 days', "
                + "'TRIAL', now(), 'system', now(), 'system', 0)", UUID.randomUUID().toString(), trial.businessId());
        disableModel3(2);

        view(open).andExpect(jsonPath("$.current.grandfathering.untilSource").value("NONE"))
                .andExpect(jsonPath("$.current.grandfathering.until").doesNotExist());
        view(trial).andExpect(jsonPath("$.current.outsideCurrentConfiguration").value(true))
                .andExpect(jsonPath("$.current.grandfathering").doesNotExist()); // Q6
    }

    @Test
    void ac4ATransitionDateEndsAnOpenEndedSubscriptionAtTheStartOfTheNextLocalDayAndIsBoundToTheConfigurationVersion() throws Exception {
        String[][] cases = {
                {"UTC", "2026-12-31", "2027-01-01T00:00:00Z"},
                {"Africa/Addis_Ababa", "2026-12-31", "2026-12-31T21:00:00Z"},
                {"America/Chicago", "2026-12-31", "2027-01-01T06:00:00Z"},
                {"America/Chicago", "2027-03-14", "2027-03-15T05:00:00Z"},
                {"America/Chicago", "2027-11-07", "2027-11-08T06:00:00Z"},
                {"America/Havana", "2027-03-13", "2027-03-14T05:00:00Z"},
        };
        disableModel3(2);
        for (int i = 0; i < cases.length; i++) {
            Tenant t = restaurant("gf-tz-" + i, cases[i][0]);
            String id = manual(jdbc, t.businessId(), "MODEL_3", 1, null, Instant.now().minus(Duration.ofDays(1)), admin.getId());
            setTransitionTo(t, cases[i][1]).andExpect(status().isOk())
                    .andExpect(jsonPath("$.grandfathering.untilSource").value("TRANSITION_DATE"))
                    .andExpect(jsonPath("$.grandfathering.until").value(cases[i][2]));
            assertThat(transitionDate(t)).as(cases[i][0]).isEqualTo(Instant.parse(cases[i][2]));
            assertThat(jdbc.queryForObject("select detail from admin_audit_event where action = 'SUBSCRIPTION_TRANSITION_SET' and target_id = ?",
                    String.class, id)).isEqualTo("config=2;transition=" + cases[i][2]);
        }
    }

    @Test
    void ac4SetAndClearAreRefusedOutsideOpenEndedGrandfatheredSubscriptionsAndChangeNothing() throws Exception {
        Tenant withEnd = data.tenant("gf-with-end");
        manual(jdbc, withEnd.businessId(), "MODEL_3", 1, Instant.now().plus(Duration.ofDays(40)), Instant.now().minus(Duration.ofDays(1)), admin.getId());
        Tenant enabled = data.tenant("gf-enabled");
        manual(jdbc, enabled.businessId(), "MODEL_4", 1, null, Instant.now().minus(Duration.ofDays(1)), admin.getId());
        Tenant open = data.tenant("gf-open-refusals");
        manual(jdbc, open.businessId(), "MODEL_3", 1, null, Instant.now().minus(Duration.ofDays(1)), admin.getId());
        Tenant none = data.tenant("gf-none");
        disableModel3(2);

        setTransitionTo(withEnd, "2026-12-31").andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD).value(hasItem("This subscription ends on its period end; a transition date isn't allowed")));
        setTransitionTo(enabled, "2026-12-31").andExpect(status().isBadRequest())
                .andExpect(jsonPath(FIELD).value(hasItem("Only a grandfathered subscription can get a transition date")));
        setTransition(none, "{\"transitionDate\":\"2026-12-31\",\"rowVersion\":0}").andExpect(status().isNotFound());
        for (String bad : new String[] {"2020-01-01", "2026-02-30", "2026-12-31T00:00:00Z", "31/12/2026", ""}) {
            setTransitionTo(open, bad).andExpect(status().isBadRequest());
        }
        setTransition(open, "{\"transitionDate\":\"2026-12-31\",\"rowVersion\":9}").andExpect(status().isConflict());
        setTransition(open, "{\"rowVersion\":0}").andExpect(status().isBadRequest());

        assertThat(transitionDate(open)).isNull();
        assertThat(transitionDate(withEnd)).isNull();
        assertThat(audits("SUBSCRIPTION_TRANSITION_SET")).isZero();
        assertThat(audits("SUBSCRIPTION_CHANGE_REJECTED")).isPositive(); // AC14: rejections are audited

        setTransitionTo(open, "2026-12-31").andExpect(status().isOk());
        clearTransition(open).andExpect(status().isOk()).andExpect(jsonPath("$.grandfathering.untilSource").value("NONE"));
        assertThat(transitionDate(open)).isNull();
        assertThat(jdbc.queryForObject("select detail from admin_audit_event where action = 'SUBSCRIPTION_TRANSITION_CLEARED'", String.class))
                .isEqualTo("config=2;transition=2026-12-31T21:00:00Z"); // the restaurant's default zone is Africa/Addis_Ababa
        clearTransition(withEnd).andExpect(status().isBadRequest());
    }

    @Test
    void ac5WhileGrandfatheredThePeriodEndCanBeExtendedButNotShortened() throws Exception {
        Tenant t = data.tenant("gf-q11");
        Instant end = Instant.now().plus(Duration.ofDays(40)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        manual(jdbc, t.businessId(), "MODEL_3", 1, end, Instant.now().minus(Duration.ofDays(1)), admin.getId());
        Tenant other = data.tenant("gf-q11-enabled");
        manual(jdbc, other.businessId(), "MODEL_4", 1, end, Instant.now().minus(Duration.ofDays(1)), admin.getId());
        disableModel3(2);

        patchSubscription(t, "{\"periodEnd\":\"" + end.minus(Duration.ofDays(10)) + "\",\"rowVersion\":0}").andExpect(status().isBadRequest())
                .andExpect(jsonPath(PERIOD_FIELD).value(hasItem("The end date can only be extended while the subscription is grandfathered")));
        patchSubscription(t, "{\"periodEnd\":\"" + end.plus(Duration.ofDays(10)) + "\",\"rowVersion\":0}").andExpect(status().isOk());
        patchSubscription(other, "{\"periodEnd\":\"" + end.minus(Duration.ofDays(10)) + "\",\"rowVersion\":0}").andExpect(status().isOk()); // not grandfathered
    }

    @Test
    void ac6APeriodEndOnAnOpenEndedSubscriptionBecomesAuthoritativeAndClearsTheTransitionDateEvenWhenEarlier() throws Exception {
        Tenant t = restaurant("gf-q12", "UTC");
        manual(jdbc, t.businessId(), "MODEL_3", 1, null, Instant.now().minus(Duration.ofDays(1)), admin.getId());
        disableModel3(2);
        setTransitionTo(t, "2026-12-31").andExpect(status().isOk());

        String earlier = "2026-12-01T00:00:00Z"; // D-M6-2
        patchSubscription(t, "{\"periodEnd\":\"" + earlier + "\",\"rowVersion\":" + rowVersion(t) + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.grandfathering.untilSource").value("PERIOD_END"))
                .andExpect(jsonPath("$.grandfathering.until").value(earlier));
        assertThat(transitionDate(t)).isNull();
        assertThat(jdbc.queryForObject("select detail from admin_audit_event where action = 'SUBSCRIPTION_UPDATED' order by occurred_at desc limit 1",
                String.class)).contains("transitionDate 2027-01-01T00:00:00Z -> cleared").contains("periodEnd null -> " + earlier);
    }

    @Test
    void ac11ATransitionDateFromAnEarlierGrandfatheringPeriodNeverAppliesButTheSameDateSetAgainDoes() throws Exception {
        Tenant t = restaurant("gf-cycle", "UTC");
        manual(jdbc, t.businessId(), "MODEL_3", 1, null, Instant.now().minus(Duration.ofDays(1)), admin.getId());
        disableModel3(2);
        setTransitionTo(t, "2026-12-31").andExpect(status().isOk());

        enableModel3(3);
        view(t).andExpect(jsonPath("$.current.grandfathering").doesNotExist());
        disableModel3(4);
        view(t).andExpect(jsonPath("$.current.grandfathering.untilSource").value("NONE"))
                .andExpect(jsonPath("$.current.grandfathering.transitionDateIgnored").value(true));

        setTransitionTo(t, "2026-12-31").andExpect(status().isOk()); // same date, new period: a new record under version 4
        config(jdbc, 5, "SETTING_2", false, false, true, admin.getId()); // Model 3 still disabled
        view(t).andExpect(jsonPath("$.current.grandfathering.untilSource").value("TRANSITION_DATE"))
                .andExpect(jsonPath("$.current.grandfathering.transitionDateIgnored").value(false));
    }

    @Test
    void ac11aATransitionDateWithoutAMatchingReadableAuditRecordIsIgnored() throws Exception {
        disableModel3(2);
        Tenant sqlOnly = restaurant("gf-sql-only", "UTC");
        String a = manual(jdbc, sqlOnly.businessId(), "MODEL_3", 1, null, Instant.now().minus(Duration.ofDays(1)), admin.getId());
        jdbc.update("update business_subscription set transition_date = '2026-12-31T00:00:00Z' where id = ?", a); // outside the API

        Tenant mismatch = restaurant("gf-mismatch", "UTC");
        String b = manual(jdbc, mismatch.businessId(), "MODEL_3", 1, null, Instant.now().minus(Duration.ofDays(1)), admin.getId());
        jdbc.update("update business_subscription set transition_date = '2026-12-31T00:00:00Z' where id = ?", b);
        auditRow(b, "config=2;transition=2026-12-20T00:00:00Z");

        Tenant garbled = restaurant("gf-garbled", "UTC");
        String c = manual(jdbc, garbled.businessId(), "MODEL_3", 1, null, Instant.now().minus(Duration.ofDays(1)), admin.getId());
        jdbc.update("update business_subscription set transition_date = '2026-12-31T00:00:00Z' where id = ?", c);
        auditRow(c, "transition 2026-12-31 under version two");

        for (Tenant t : new Tenant[] {sqlOnly, mismatch, garbled}) {
            view(t).andExpect(jsonPath("$.current.grandfathering.untilSource").value("NONE"))
                    .andExpect(jsonPath("$.current.grandfathering.transitionDateIgnored").value(true));
        }
    }

    private void auditRow(String subscriptionId, String detail) {
        jdbc.update("insert into admin_audit_event (id, occurred_at, actor_label, action, target_type, target_id, outcome, detail) "
                + "values (?, now(), 'system', 'SUBSCRIPTION_TRANSITION_SET', 'BUSINESS_SUBSCRIPTION', ?, 'SUCCESS', ?)", UUID.randomUUID().toString(), subscriptionId, detail);
    }

    @Test
    void ac17TheManualEndActionIsUnchangedForGrandfatheredSubscriptionsAndTheJobThenDoesNothing() throws Exception {
        Tenant t = data.tenant("gf-manual-end");
        manual(jdbc, t.businessId(), "MODEL_3", 1, Instant.now().plus(Duration.ofDays(40)), Instant.now().minus(Duration.ofDays(1)), admin.getId());
        disableModel3(2);
        for (boolean flag : new boolean[] {false, true}) {
            grandfathering.setEnabled(flag);
            if (flag) manual(jdbc, t.businessId(), "MODEL_3", 1, Instant.now().plus(Duration.ofDays(40)), Instant.now().minus(Duration.ofDays(1)), admin.getId());
            patchSubscription(t, "{\"status\":\"EXPIRED\",\"rowVersion\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRED"));
        }
        assertThat(audits("SUBSCRIPTION_UPDATED")).isEqualTo(2);
        assertThat(grandfathering.runEnd()).isZero();
        assertThat(audits("GRANDFATHERING_ENDED")).isZero();
    }

    @Test
    void ac1TheSaveDialogPreviewCountsWithoutWritingAndTheListFiltersGrandfatheredRestaurants() throws Exception {
        Tenant overdue = data.tenant("gf-preview-overdue");
        manual(jdbc, overdue.businessId(), "MODEL_3", 1, Instant.now().minus(Duration.ofDays(2)), Instant.now().minus(Duration.ofDays(60)), admin.getId());
        Tenant open = data.tenant("gf-preview-open");
        manual(jdbc, open.businessId(), "MODEL_2", 1, null, Instant.now().minus(Duration.ofDays(60)), admin.getId());
        Tenant kept = data.tenant("gf-preview-kept");
        manual(jdbc, kept.businessId(), "MODEL_4", 1, Instant.now().plus(Duration.ofDays(30)), Instant.now().minus(Duration.ofDays(60)), admin.getId());
        String before = jdbc.queryForObject("select md5(string_agg(s::text, '|' order by s::text)) from business_subscription s", String.class);

        mockMvc.perform(get("/api/admin/grandfathering/preview").param("setting", "SETTING_2").param("models", "MODEL_4").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grandfathered").value(2))
                .andExpect(jsonPath("$.byPlan.MODEL_3").value(1))
                .andExpect(jsonPath("$.byPlan.MODEL_2").value(1))
                .andExpect(jsonPath("$.overdue").value(1))
                .andExpect(jsonPath("$.openEndedWithoutDate").value(1))
                .andExpect(jsonPath("$.transitionDatesNotApplying").value(0));
        mockMvc.perform(get("/api/admin/grandfathering/preview").param("setting", "SETTING_1").header("Authorization", bearer))
                .andExpect(jsonPath("$.grandfathered").value(3));
        assertThat(jdbc.queryForObject("select md5(string_agg(s::text, '|' order by s::text)) from business_subscription s", String.class)).isEqualTo(before);

        config(jdbc, 2, "SETTING_2", false, false, true, admin.getId()); // only Model 4
        mockMvc.perform(get("/api/admin/businesses").param("grandfathered", "true").header("Authorization", bearer))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[?(@.grandfathered == true)].name").value(org.hamcrest.Matchers.containsInAnyOrder("gf-preview-overdue Restaurant", "gf-preview-open Restaurant")));
        mockMvc.perform(get("/api/admin/businesses").header("Authorization", bearer))
                .andExpect(jsonPath("$.items[?(@.name == 'gf-preview-kept Restaurant')].grandfathered").value(org.hamcrest.Matchers.contains(false)));
    }
}
