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
import java.util.UUID;

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.support.MonetizationFixtures.subscription;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M5 trial end date (Option A, PO 2026-11-02): the Super Admin extends a trial to a calendar date, meaning the end
 * of that day in the restaurant's configured time zone. The stored, exclusive end is the start of the following local day
 * (java.time, the restaurant's zone, never the browser's). A date and an instant together, a date with an end, or a date
 * on anything but a running trial are refused. Admin and owner reads carry the restaurant's time zone for display.
 */
class TrialEndDateTest extends AbstractIntegrationTest {

    private static final String FIELD = "$.fieldErrors[?(@.field == 'periodEndDate')].message";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TrialService trials;
    @Autowired private PlanRequestService planRequests;
    @Autowired private MonetizationClock clock;

    private User admin;
    private String bearer;

    @BeforeEach
    void setUp() {
        admin = data.admin("trial-end-date");
        bearer = data.adminBearer(admin);
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        trials.setEnabled(true);
    }

    @AfterEach
    void reset() {
        trials.setEnabled(false);
        planRequests.setOwnerPlansVisible(false);
        clock.reset();
    }

    private Tenant restaurantWithTrial(String label, String timeZone) {
        Tenant t = data.tenant(label);
        jdbc.update("update business set time_zone = ? where id = ?", timeZone, t.businessId());
        Instant start = Instant.now().minus(Duration.ofDays(1));
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, "
                + "created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_1', 'TRIAL', 1, ?, 'TRIAL', ?, 'system', ?, 'system', 0)",
                UUID.randomUUID().toString(), t.businessId(), Timestamp.from(start.plus(Duration.ofDays(30))), Timestamp.from(start), Timestamp.from(start));
        return t;
    }

    private ResultActions extend(Tenant t, String body) throws Exception {
        return mockMvc.perform(patch("/api/admin/businesses/{id}/subscription", t.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions extendTo(Tenant t, String date) throws Exception {
        return extend(t, "{\"periodEndDate\":\"" + date + "\",\"rowVersion\":" + rowVersion(t) + "}");
    }

    private int rowVersion(Tenant t) {
        return jdbc.queryForObject("select row_version from business_subscription where business_id = ? and status <> 'EXPIRED'", Integer.class, t.businessId());
    }

    private Instant periodEnd(Tenant t) {
        return jdbc.queryForObject("select period_end from business_subscription where business_id = ? and source = 'TRIAL'", Timestamp.class, t.businessId()).toInstant();
    }

    @Test
    void theChosenDateEndsAtTheStartOfTheNextLocalDayInTheRestaurantsTimeZone() throws Exception {
        Tenant utc = restaurantWithTrial("end-utc", "UTC");
        Tenant addis = restaurantWithTrial("end-addis", "Africa/Addis_Ababa");
        Tenant chicago = restaurantWithTrial("end-chicago", "America/Chicago");

        extendTo(utc, "2026-12-31").andExpect(status().isOk());
        extendTo(addis, "2026-12-31").andExpect(status().isOk());
        extendTo(chicago, "2026-12-31").andExpect(status().isOk()).andExpect(jsonPath("$.periodEnd").value("2027-01-01T06:00:00Z"));

        assertThat(periodEnd(utc)).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(periodEnd(addis)).isEqualTo(Instant.parse("2026-12-31T21:00:00Z"));
        assertThat(periodEnd(chicago)).isEqualTo(Instant.parse("2027-01-01T06:00:00Z"));
        assertThat(jdbc.queryForObject("select detail from admin_audit_event where action = 'TRIAL_EXTENDED' and detail like '%2027-01-01T06:00:00Z%'",
                String.class)).contains("-> 2027-01-01T06:00:00Z");
        mockMvc.perform(get("/api/admin/businesses/{id}/subscription", chicago.businessId()).header("Authorization", bearer))
                .andExpect(jsonPath("$.timeZone").value("America/Chicago"))
                .andExpect(jsonPath("$.current.periodEnd").value("2027-01-01T06:00:00Z"));
    }

    @Test
    void daylightSavingTransitionsAndAMissingMidnightUseTheRealStartOfTheNextDay() throws Exception {
        String[][] cases = {
                {"America/Chicago", "2027-03-13", "2027-03-14T06:00:00Z"}, // still CST at the next midnight
                {"America/Chicago", "2027-03-14", "2027-03-15T05:00:00Z"}, // CDT after spring forward
                {"America/Chicago", "2027-11-06", "2027-11-07T05:00:00Z"}, // still CDT at the next midnight
                {"America/Chicago", "2027-11-07", "2027-11-08T06:00:00Z"}, // CST after fall back
                {"America/Havana", "2027-03-13", "2027-03-14T05:00:00Z"}, // midnight doesn't exist: the day starts at 01:00
        };
        for (int i = 0; i < cases.length; i++) {
            Tenant t = restaurantWithTrial("end-dst-" + i, cases[i][0]);
            extendTo(t, cases[i][1]).andExpect(status().isOk());
            assertThat(periodEnd(t)).as(cases[i][0] + " " + cases[i][1]).isEqualTo(Instant.parse(cases[i][2]));
        }
    }

    @Test
    void conflictingAmbiguousOrInvalidInputsAreRefusedAndChangeNothing() throws Exception {
        Tenant t = restaurantWithTrial("end-invalid", "America/Chicago");
        Instant before = periodEnd(t);

        extend(t, "{\"periodEndDate\":\"2026-12-31\",\"periodEnd\":\"2027-01-01T06:00:00Z\",\"rowVersion\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath(FIELD).value(hasItem("Send a date or an end time, not both")));
        extend(t, "{\"periodEndDate\":\"2026-12-31\",\"status\":\"EXPIRED\",\"rowVersion\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath(FIELD).value(hasItem("Extending and ending are separate actions")));
        for (String bad : new String[] {"2026-02-30", "2026-12-31T00:00:00Z", "2026-12-31T05:00", "31/12/2026", ""}) {
            extend(t, "{\"periodEndDate\":\"" + bad + "\",\"rowVersion\":0}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath(FIELD).value(hasItem("Use a calendar date (YYYY-MM-DD)")));
        }
        extend(t, "{\"periodEndDate\":\"2020-01-01\",\"rowVersion\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath(FIELD).value(hasItem("Choose a date in the future")));
        assertThat(periodEnd(t)).isEqualTo(before);
        assertThat(rowVersion(t)).isZero();

        Tenant manual = data.tenant("end-manual");
        subscription(jdbc, manual.businessId(), "MODEL_1", 1, admin.getId());
        extend(manual, "{\"periodEndDate\":\"2026-12-31\",\"rowVersion\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath(FIELD).value(hasItem("Only a running trial can be extended to a date")));
        assertThat(jdbc.queryForObject("select period_end is null from business_subscription where business_id = ?", Boolean.class, manual.businessId())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = 'TRIAL_EXTENDED'", Integer.class)).isZero();
    }

    @Test
    void theTrialRunsThroughTheWholeChosenLocalDayAndExpiresAtTheNextMidnight() throws Exception {
        planRequests.setOwnerPlansVisible(true);
        Tenant t = restaurantWithTrial("end-boundary", "America/Chicago");
        extendTo(t, "2026-12-31").andExpect(status().isOk());

        clock.setOffset(Duration.between(Instant.now(), Instant.parse("2027-01-01T05:59:00Z"))); // Dec 31, 23:59 in Chicago
        mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(t)))
                .andExpect(jsonPath("$.trial.effectiveStatus").value("TRIAL"))
                .andExpect(jsonPath("$.trial.daysLeft").value(1))
                .andExpect(jsonPath("$.trial.endsAt").value("2027-01-01T06:00:00Z"))
                .andExpect(jsonPath("$.trial.timeZone").value("America/Chicago"));
        assertThat(trials.runExpiry()).isZero();

        clock.setOffset(Duration.between(Instant.now(), Instant.parse("2027-01-01T06:00:01Z"))); // Jan 1, 00:00:01 in Chicago
        mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(t)))
                .andExpect(jsonPath("$.trial.effectiveStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.trial.daysLeft").value(0));
        assertThat(trials.runExpiry()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select ended_at = period_end from business_subscription where business_id = ?", Boolean.class, t.businessId())).isTrue();
    }

    @Test
    void automaticTrialsKeepTheirThirtyDaysAndCarryTheRestaurantsTimeZone() throws Exception {
        planRequests.setOwnerPlansVisible(true);
        String token = com.jayway.jsonpath.JsonPath.read(TrialTestSupport.register(mockMvc, "end-auto").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.token");
        String businessId = TrialTestSupport.businessOf(jdbc, "end-auto");
        assertThat(jdbc.queryForObject("select period_end - created_at = interval '30 days' from business_subscription where business_id = ?",
                Boolean.class, businessId)).isTrue();
        mockMvc.perform(get("/api/plan").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.trial.timeZone").value("Africa/Addis_Ababa"))
                .andExpect(jsonPath("$.trial.daysLeft").value(30));
    }
}
