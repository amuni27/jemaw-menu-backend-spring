package com.agafari.com.trial;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.TrialService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M5 (AC1–AC4, AC10; D-M5-3): a 30-day Model 1 trial starts inside the registration transaction when trials are
 * enabled and Setting 1 is current. Off by default; never under Setting 2 or while not configured; never for restaurants
 * registered earlier; at most one trial per restaurant.
 */
class TrialStartTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TrialService trials;
    @Autowired private MonetizationClock clock;
    @Autowired private Environment environment;

    private User admin;

    @BeforeEach
    void setUp() {
        admin = data.admin("trial-start");
    }

    @AfterEach
    void reset() {
        trials.setEnabled(false);
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
    void ac2UnderSetting1ANewRestaurantGetsExactlyOneThirtyDayTrialInTheRegistrationTransaction() throws Exception {
        trials.setEnabled(true);
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        register(mockMvc, "trial-new").andExpect(status().isCreated());
        String businessId = businessOf(jdbc, "trial-new");

        Map<String, Object> trial = jdbc.queryForMap("select * from business_subscription where business_id = ?", businessId);
        assertThat(trial).containsEntry("plan_code", "MODEL_1").containsEntry("status", "TRIAL").containsEntry("source", "TRIAL")
                .containsEntry("config_version", 1).containsEntry("created_by_user_id", "system");
        Duration length = Duration.between(((Timestamp) trial.get("created_at")).toInstant(), ((Timestamp) trial.get("period_end")).toInstant());
        assertThat(length).isEqualTo(Duration.ofDays(30));
        assertThat(jdbc.queryForObject("select action || '/' || actor_label from admin_audit_event where action = 'TRIAL_STARTED'", String.class))
                .isEqualTo("TRIAL_STARTED/system");
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
        assertThat(trialsOf(businessOf(jdbc, "after-trials"))).isEqualTo(1);
    }

    @Test
    void ac4AtMostOneTrialPerRestaurantEverAndTrialRowsAreConsistent() throws Exception {
        trials.setEnabled(true);
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        register(mockMvc, "one-trial").andExpect(status().isCreated());
        String businessId = businessOf(jdbc, "one-trial");
        jdbc.update("update business_subscription set status = 'EXPIRED', ended_at = now() where business_id = ?", businessId);

        String insert = "insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, "
                + "created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, ?, ?, 1, %s, ?, now(), 'system', now(), 'system', 0)";
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
