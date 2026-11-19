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
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import com.jayway.jsonpath.JsonPath;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.trial.TrialTestSupport.businessOf;
import static com.agafari.com.trial.TrialTestSupport.register;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M5 (AC1–AC4, AC10), realigned by AGF BE-2 (register §45 D-3 B, §48; D-M5-3 superseded for new registrations):
 * a registration never starts a trial, whatever the flag and setting. Under Setting 1 with trials enabled the owner starts
 * the 30-day Model 1 trial explicitly (BE-1). Off by default; never under Setting 2 or while not configured; never for
 * restaurants registered earlier; at most one trial per restaurant.
 */
class TrialStartTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TrialService trials;
    @Autowired private MonetizationClock clock;
    @Autowired private Environment environment;
    @Autowired private PlanRequestService planRequests;

    private User admin;

    @BeforeEach
    void setUp() {
        admin = data.admin("trial-start");
    }

    @AfterEach
    void reset() {
        trials.setEnabled(false);
        planRequests.setOwnerPlansVisible(false);
        clock.reset();
    }

    private int trialsOf(String businessId) {
        return jdbc.queryForObject("select count(*) from business_subscription where business_id = ? and source = 'TRIAL'", Integer.class, businessId);
    }

    @Test
    void ac1WithTrialsDisabledByDefaultRegistrationCreatesNoTrial() throws Exception {
        assertThat(environment.getProperty("app.monetization.trials-enabled", Boolean.class, false)).isFalse();
        assertThat(environment.getProperty("app.monetization.trial-duration")).isEqualTo("P30D");
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        register(mockMvc, "trials-off").andExpect(status().isCreated());
        assertThat(trialsOf(businessOf(jdbc, "trials-off"))).isZero();
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action like 'TRIAL%'", Integer.class)).isZero();
    }

    @Test
    void ac2UnderSetting1ANewRestaurantGetsNoAutomaticTrialAndTheOwnerStartsExactlyOneThirtyDayTrialExplicitly() throws Exception {
        trials.setEnabled(true);
        planRequests.setOwnerPlansVisible(true);
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        String token = JsonPath.read(register(mockMvc, "trial-new").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.token");
        String businessId = businessOf(jdbc, "trial-new");
        assertThat(trialsOf(businessId)).as("BE-2: no automatic trial").isZero();
        assertThat(jdbc.queryForObject("select count(*) from trial_intent where business_id = ?", Integer.class, businessId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action like 'TRIAL%'", Integer.class)).isZero();

        String ownerId = jdbc.queryForObject("select owner_user_id from business where id = ?", String.class, businessId);
        jdbc.update("update users set email_verified_at = now() where id = ?", ownerId); // Q2: verification is operational in tests
        mockMvc.perform(get("/api/plan").header("Authorization", "Bearer " + token)).andExpect(jsonPath("$.trialEligible").value(true));
        mockMvc.perform(post("/api/plan/trial").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedConfigVersion\":1}")).andExpect(status().isCreated());

        Map<String, Object> trial = jdbc.queryForMap("select * from business_subscription where business_id = ?", businessId);
        assertThat(trial).containsEntry("plan_code", "MODEL_1").containsEntry("status", "TRIAL").containsEntry("source", "TRIAL")
                .containsEntry("config_version", 1).containsEntry("created_by_user_id", ownerId);
        Duration length = Duration.between(((Timestamp) trial.get("created_at")).toInstant(), ((Timestamp) trial.get("period_end")).toInstant());
        assertThat(length).isEqualTo(Duration.ofDays(30));
        assertThat(jdbc.queryForObject("select actor_user_id from admin_audit_event where action = 'TRIAL_STARTED'", String.class)).isEqualTo(ownerId);
        mockMvc.perform(get("/api/plan").header("Authorization", "Bearer " + token)).andExpect(jsonPath("$.trialEligible").value(false));
    }

    @Test
    void ac3NoTrialUnderSetting2OrWhileNotConfigured() throws Exception {
        trials.setEnabled(true);
        register(mockMvc, "trial-unconfigured").andExpect(status().isCreated());
        assertThat(trialsOf(businessOf(jdbc, "trial-unconfigured"))).isZero();
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        register(mockMvc, "trial-setting2").andExpect(status().isCreated());
        assertThat(trialsOf(businessOf(jdbc, "trial-setting2"))).isZero();
    }

    @Test
    void ac4RestaurantsRegisteredEarlierNeverGetAnAutomaticTrial() throws Exception {
        Tenant earlier = data.tenant("before-trials");
        trials.setEnabled(true);
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        register(mockMvc, "after-trials").andExpect(status().isCreated());
        assertThat(trials.runExpiry()).isZero(); // the job never starts trials
        assertThat(trialsOf(earlier.businessId())).isZero();
        assertThat(trialsOf(businessOf(jdbc, "after-trials"))).as("BE-2: new registrations neither").isZero();
    }

    @Test
    void ac4AtMostOneTrialPerRestaurantEverAndTrialRowsAreConsistent() throws Exception {
        trials.setEnabled(true);
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        String businessId = data.tenant("one-trial").businessId();
        String insert = "insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, "
                + "created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, ?, ?, 1, %s, ?, now(), 'system', now(), 'system', 0)";
        jdbc.update(insert.formatted("now() + interval '30 days'"), UUID.randomUUID().toString(), businessId, "MODEL_1", "TRIAL", "TRIAL"); // its earlier trial
        jdbc.update("update business_subscription set status = 'EXPIRED', ended_at = now() where business_id = ?", businessId);

        assertThatThrownBy(() -> jdbc.update(insert.formatted("now() + interval '30 days'"), UUID.randomUUID().toString(), businessId, "MODEL_1", "TRIAL", "TRIAL"))
                .hasMessageContaining("uk_business_subscription_one_trial");
        Tenant other = data.tenant("trial-checks");
        assertThatThrownBy(() -> jdbc.update(insert.formatted("null"), UUID.randomUUID().toString(), other.businessId(), "MODEL_1", "TRIAL", "TRIAL"))
                .hasMessageContaining("ck_business_subscription_trial_end");
        assertThatThrownBy(() -> jdbc.update(insert.formatted("now() + interval '30 days'"), UUID.randomUUID().toString(), other.businessId(), "MODEL_1", "TRIAL", "MANUAL"))
                .hasMessageContaining("ck_business_subscription_trial_source");
        assertThatThrownBy(() -> jdbc.update(insert.formatted("now() + interval '30 days'"), UUID.randomUUID().toString(), other.businessId(), "MODEL_2", "ACTIVE", "TRIAL"))
                .hasMessageContaining("ck_business_subscription_trial_source");
    }
}
