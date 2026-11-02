package com.agafari.com.trial;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.PlanRequestService;
import com.agafari.com.service.TrialService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.trial.TrialTestSupport.businessOf;
import static com.agafari.com.trial.TrialTestSupport.register;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M5 recovery (V16, AC-R1…AC-R9): an eligible registration commits a durable trial intent; the trial is created
 * after the commit or, after a failure or a process stop, by the existing daily job. Exactly one trial, starting when it
 * is created. Pending intents are kept while Setting 2 is current or trials are disabled; failing intents can't starve
 * newer ones. Failures are made real by a temporary database trigger that refuses trials for restaurants named
 * "Trial fail…" (dropped after each test).
 */
class TrialRecoveryTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TrialService trials;
    @Autowired private PlanRequestService planRequests;
    @Autowired private MonetizationClock clock;

    private User admin;

    @BeforeEach
    void setUp() {
        admin = data.admin("trial-recovery");
        config(jdbc, 1, "SETTING_1", false, false, false, admin.getId());
        trials.setEnabled(true);
    }

    @AfterEach
    void reset() {
        jdbc.execute("drop trigger if exists test_failing_trial on business_subscription");
        jdbc.execute("drop function if exists test_failing_trial()");
        trials.setEnabled(false);
        trials.setAfterCommitEnabled(true);
        trials.setRecoveryBatchSize(100);
        planRequests.setOwnerPlansVisible(false);
        clock.reset();
    }

    private void failTrialsForRestaurantsNamedFail() {
        jdbc.execute("""
                create function test_failing_trial() returns trigger language plpgsql as $$
                begin
                    if exists (select 1 from business b where b.id = new.business_id and b.name like 'Trial fail%') then
                        raise exception 'test: trial insert refused';
                    end if;
                    return new;
                end
                $$""");
        jdbc.execute("create trigger test_failing_trial before insert on business_subscription for each row execute function test_failing_trial()");
    }

    private void stopFailing() {
        jdbc.execute("drop trigger test_failing_trial on business_subscription");
    }

    /** A registration whose process stops right after the commit: the after-commit trial step never runs. */
    private String interruptedRegistration(String label) throws Exception {
        trials.setAfterCommitEnabled(false);
        try {
            register(mockMvc, label).andExpect(status().isCreated());
        } finally {
            trials.setAfterCommitEnabled(true);
        }
        return businessOf(jdbc, label);
    }

    private String registered(String label) throws Exception {
        register(mockMvc, label).andExpect(status().isCreated());
        return businessOf(jdbc, label);
    }

    private Map<String, Object> intent(String businessId) {
        return jdbc.queryForMap("select * from trial_intent where business_id = ?", businessId);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int trialsOf(String businessId) {
        return count("select count(*) from business_subscription where business_id = ? and source = 'TRIAL'", businessId);
    }

    private String trialIdOf(String businessId) {
        return jdbc.queryForObject("select id from business_subscription where business_id = ? and source = 'TRIAL'", String.class, businessId);
    }

    @Test
    void acR1R2AnEligibleRegistrationCommitsAnIntentThatTheAfterCommitStepStarts() throws Exception {
        String businessId = registered("intent-ok");
        assertThat(intent(businessId)).containsEntry("status", "STARTED").containsEntry("resolution", "AFTER_COMMIT")
                .containsEntry("config_version", 1).containsEntry("failed_attempts", 0).containsEntry("subscription_id", trialIdOf(businessId));
        assertThat(jdbc.queryForObject("select detail from admin_audit_event where action = 'TRIAL_STARTED'", String.class))
                .contains("path=AFTER_COMMIT").contains("intentConfig=1");
    }

    @Test
    void acR1NoIntentWhenNotEligible() throws Exception {
        trials.setEnabled(false);
        assertThat(count("select count(*) from trial_intent where business_id = ?", registered("intent-flag-off"))).isZero();
        trials.setEnabled(true);
        config(jdbc, 2, "SETTING_2", true, false, true, admin.getId());
        assertThat(count("select count(*) from trial_intent where business_id = ?", registered("intent-setting2"))).isZero();
    }

    @Test
    void acR3AfterAProcessStopTheJobCreatesTheTrialAndItsThirtyDaysStartThen() throws Exception {
        String businessId = interruptedRegistration("intent-interrupted");
        assertThat(intent(businessId)).containsEntry("status", "PENDING").containsEntry("config_version", 1);
        assertThat(trialsOf(businessId)).isZero();

        clock.setOffset(Duration.ofDays(2)); // the job runs two days later
        Instant jobTime = Instant.now().plus(Duration.ofDays(2));
        assertThat(trials.runRecovery()).isEqualTo(1);

        assertThat(intent(businessId)).containsEntry("status", "STARTED").containsEntry("resolution", "RETRY")
                .containsEntry("subscription_id", trialIdOf(businessId));
        Map<String, Object> trial = jdbc.queryForMap("select * from business_subscription where business_id = ?", businessId);
        Instant start = ((Timestamp) trial.get("created_at")).toInstant();
        assertThat(start).isCloseTo(jobTime, within(1, ChronoUnit.MINUTES)); // not retroactive to the registration
        assertThat(Duration.between(start, ((Timestamp) trial.get("period_end")).toInstant())).isEqualTo(Duration.ofDays(30));
        assertThat(jdbc.queryForObject("select detail from admin_audit_event where action = 'TRIAL_STARTED'", String.class)).contains("path=RETRY");
    }

    @Test
    void acR4AFailedStartStaysPendingIsAuditedAndIsRetriedUntilItSucceeds() throws Exception {
        failTrialsForRestaurantsNamedFail();
        String businessId = registered("fail-retry"); // the after-commit attempt fails; the registration doesn't

        assertThat(intent(businessId)).containsEntry("status", "PENDING").containsEntry("failed_attempts", 1);
        assertThat((String) intent(businessId).get("last_error")).isNotBlank().doesNotContain("test: trial insert refused");
        assertThat(intent(businessId).get("last_attempt_at")).isNotNull();
        assertThat(count("select count(*) from admin_audit_event where action = 'TRIAL_START_FAILED' and target_id = ?", businessId)).isEqualTo(1);

        assertThat(trials.runRecovery()).isZero(); // still failing
        assertThat(intent(businessId)).containsEntry("status", "PENDING").containsEntry("failed_attempts", 2);
        assertThat(trialsOf(businessId)).isZero();

        stopFailing();
        assertThat(trials.runRecovery()).isEqualTo(1);
        assertThat(intent(businessId)).containsEntry("status", "STARTED").containsEntry("resolution", "RETRY").containsEntry("failed_attempts", 2);
        assertThat(trialsOf(businessId)).isEqualTo(1);
    }

    @Test
    void acR5RetryingAgainChangesNothing() throws Exception {
        String started = registered("idempotent-started");
        String recovered = interruptedRegistration("idempotent-recovered");
        assertThat(trials.runRecovery()).isEqualTo(1);
        Map<String, Object> before = intent(recovered);
        int audits = count("select count(*) from admin_audit_event");

        assertThat(trials.runRecovery()).isZero();
        assertThat(trials.runRecovery()).isZero();

        assertThat(intent(recovered)).isEqualTo(before);
        assertThat(trialsOf(started)).isEqualTo(1);
        assertThat(trialsOf(recovered)).isEqualTo(1);
        assertThat(count("select count(*) from admin_audit_event")).isEqualTo(audits);
    }

    @Test
    void acR5aFailingOlderIntentsDontStarveNewerOnes() throws Exception {
        trials.setRecoveryBatchSize(2);
        String failA = interruptedRegistration("fail-a");
        String failB = interruptedRegistration("fail-b");
        String newer = interruptedRegistration("ok-newer");
        failTrialsForRestaurantsNamedFail();

        assertThat(trials.runRecovery()).isZero(); // the two oldest fail; the batch is full
        assertThat(intent(newer)).containsEntry("status", "PENDING").containsEntry("failed_attempts", 0);

        assertThat(trials.runRecovery()).isEqualTo(1); // never-attempted first: the newer one isn't blocked
        assertThat(intent(newer)).containsEntry("status", "STARTED").containsEntry("resolution", "RETRY");
        assertThat(intent(failA)).containsEntry("status", "PENDING");
        assertThat(intent(failB)).containsEntry("status", "PENDING");
        assertThat((int) intent(failA).get("failed_attempts") + (int) intent(failB).get("failed_attempts")).isEqualTo(3); // rotated
    }

    @Test
    void acR6R7UnderSetting2TheIntentStaysPendingAndRecoveryResumesUnderSetting1() throws Exception {
        String businessId = interruptedRegistration("intent-setting-change");
        config(jdbc, 2, "SETTING_2", true, false, true, admin.getId());
        int audits = count("select count(*) from admin_audit_event");

        assertThat(trials.runRecovery()).isZero();
        assertThat(intent(businessId)).containsEntry("status", "PENDING").containsEntry("failed_attempts", 0);
        assertThat(intent(businessId).get("last_attempt_at")).isNull();
        assertThat(trialsOf(businessId)).isZero(); // never a Model 1 trial under Setting 2
        assertThat(count("select count(*) from admin_audit_event")).isEqualTo(audits);

        config(jdbc, 3, "SETTING_1", false, false, false, admin.getId());
        assertThat(trials.runRecovery()).isEqualTo(1);
        assertThat(intent(businessId)).containsEntry("status", "STARTED").containsEntry("config_version", 1); // eligibility kept
        assertThat(jdbc.queryForObject("select config_version from business_subscription where business_id = ?", Integer.class, businessId))
                .isEqualTo(3); // the trial records the configuration current when it is created
    }

    @Test
    void acR8WhileTrialsAreDisabledIntentsStayPendingAndResumeWhenReenabled() throws Exception {
        String businessId = interruptedRegistration("intent-flag-change");
        trials.setEnabled(false);
        assertThat(trials.runRecovery()).isZero();
        assertThat(intent(businessId)).containsEntry("status", "PENDING").containsEntry("failed_attempts", 0);
        assertThat(trialsOf(businessId)).isZero();

        trials.setEnabled(true);
        assertThat(trials.runRecovery()).isEqualTo(1);
        assertThat(trialsOf(businessId)).isEqualTo(1);
    }

    @Test
    void acR6AManualStartResolvesThePendingIntent() throws Exception {
        String businessId = interruptedRegistration("intent-manual-start");
        mockMvc.perform(post("/api/admin/businesses/{id}/trial", businessId).header("Authorization", data.adminBearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"expectedConfigVersion\":1}")).andExpect(status().isCreated());

        assertThat(intent(businessId)).containsEntry("status", "STARTED").containsEntry("resolution", "MANUAL")
                .containsEntry("subscription_id", trialIdOf(businessId));
        assertThat(trials.runRecovery()).isZero();
        assertThat(trialsOf(businessId)).isEqualTo(1);
    }

    @Test
    void acR6AManualSubscriptionMeansNoTrialAndTheIntentIsResolvedWithIt() throws Exception {
        String businessId = interruptedRegistration("intent-manual-record");
        String body = mockMvc.perform(post("/api/admin/businesses/{id}/subscription", businessId).header("Authorization", data.adminBearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"planCode\":\"MODEL_1\",\"expectedConfigVersion\":1}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String subscriptionId = JsonPath.read(body, "$.id");

        assertThat(trials.runRecovery()).isEqualTo(1);
        assertThat(intent(businessId)).containsEntry("status", "NOT_STARTED").containsEntry("resolution", "SUBSCRIPTION_EXISTS")
                .containsEntry("subscription_id", subscriptionId);
        assertThat(trialsOf(businessId)).isZero();
        assertThat(jdbc.queryForObject("select detail from admin_audit_event where action = 'TRIAL_INTENT_RESOLVED' and target_id = ?",
                String.class, businessId)).contains("NOT_STARTED").contains("SUBSCRIPTION_EXISTS");
    }

    @Test
    void acR6AnExistingTrialResolvesTheIntentWithoutASecondTrial() throws Exception {
        String businessId = interruptedRegistration("intent-existing-trial");
        String trialId = UUID.randomUUID().toString();
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, "
                + "created_by_user_id, updated_at, updated_by_user_id, row_version) values (?, ?, 'MODEL_1', 'TRIAL', 1, now() + interval '30 days', "
                + "'TRIAL', now(), 'system', now(), 'system', 0)", trialId, businessId);

        assertThat(trials.runRecovery()).isEqualTo(1);
        assertThat(intent(businessId)).containsEntry("status", "STARTED").containsEntry("resolution", "EXISTING_TRIAL")
                .containsEntry("subscription_id", trialId);
        assertThat(trialsOf(businessId)).isEqualTo(1);
    }

    @Test
    void acR9TheSuperAdminSeesPendingIntentsAndThoseThatNeedAttentionButOwnersSeeNothing() throws Exception {
        failTrialsForRestaurantsNamedFail();
        String failing = registered("fail-visible"); // 1 failed attempt
        trials.runRecovery();
        trials.runRecovery(); // 3 failed attempts
        String waiting = interruptedRegistration("pending-visible");
        String bearer = data.adminBearer(admin);

        mockMvc.perform(get("/api/admin/trial-intents").header("Authorization", bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(2))
                .andExpect(jsonPath("$.needsAttention").value(1))
                .andExpect(jsonPath("$.items[0].businessId").value(failing))
                .andExpect(jsonPath("$.items[0].businessName").isNotEmpty())
                .andExpect(jsonPath("$.items[0].failedAttempts").value(3))
                .andExpect(jsonPath("$.items[0].needsAttention").value(true))
                .andExpect(jsonPath("$.items[0].lastError").isNotEmpty())
                .andExpect(jsonPath("$.items[1].businessId").value(waiting))
                .andExpect(jsonPath("$.items[1].needsAttention").value(false));
        mockMvc.perform(get("/api/admin/businesses/{id}/subscription", failing).header("Authorization", bearer))
                .andExpect(jsonPath("$.trialIntent.status").value("PENDING"))
                .andExpect(jsonPath("$.trialIntent.failedAttempts").value(3))
                .andExpect(jsonPath("$.trialIntent.needsAttention").value(true));

        planRequests.setOwnerPlansVisible(true);
        String token = JsonPath.read(register(mockMvc, "fail-owner").andReturn().getResponse().getContentAsString(), "$.token");
        mockMvc.perform(get("/api/plan").header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.trialIntent").doesNotExist())
                .andExpect(jsonPath("$.trial").doesNotExist());
        mockMvc.perform(get("/api/admin/trial-intents").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }
}
