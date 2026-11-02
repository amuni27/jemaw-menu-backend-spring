package com.agafari.com.trial;

import com.agafari.com.security.AdminAuditService;
import com.agafari.com.security.EmailVerificationService;
import com.agafari.com.service.TrialService;
import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.trial.TrialTestSupport.businessOf;
import static com.agafari.com.trial.TrialTestSupport.register;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M5 (AC2, D-M5-3 as restored by the PO on 2026-11-02; recovery AC-R1, AC-R4): the trial starts after the
 * registration commits, in its own transaction. A trial failure never fails the registration: the trial rolls back
 * completely, TRIAL_START_FAILED is recorded against the restaurant and the durable intent stays pending for the daily
 * retry. A registration that rolls back gets no trial and no intent.
 */
class TrialRegistrationAfterCommitTest extends AbstractIntegrationTest {

    @MockitoSpyBean private AdminAuditService audit;
    @MockitoSpyBean private EmailVerificationService emailVerification;
    @Autowired private TrialService trials;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        config(jdbc, 1, "SETTING_1", false, false, false, data.admin("after-commit-trial").getId());
        trials.setEnabled(true);
    }

    @AfterEach
    void restore() {
        trials.setEnabled(false);
        reset(audit, emailVerification);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void ac2AFailingTrialLeavesTheRegistrationSuccessfulWithNoPartialTrialAndAuditsTheFailure() throws Exception {
        // fails after the trial row is flushed, so the trial's own transaction must roll it back
        doThrow(new IllegalStateException("audit store unavailable")).when(audit)
                .success(eq(TrialService.STARTED), any(), any(), any(), any(), any());

        register(mockMvc, "trial-fails").andExpect(status().isCreated()).andExpect(jsonPath("$.token").isNotEmpty());

        String businessId = businessOf(jdbc, "trial-fails"); // owner and restaurant committed
        assertThat(count("select count(*) from business_hours where business_id = ?", businessId)).isEqualTo(7);
        assertThat(count("select count(*) from business_subscription")).isZero();
        assertThat(count("select count(*) from admin_audit_event where action = 'TRIAL_STARTED'")).isZero();
        Map<String, Object> failed = jdbc.queryForMap("select * from admin_audit_event where action = 'TRIAL_START_FAILED'");
        assertThat(failed).containsEntry("outcome", "FAILURE").containsEntry("actor_label", "system")
                .containsEntry("target_type", "BUSINESS").containsEntry("target_id", businessId)
                .containsEntry("detail", "unexpected_error");
        assertThat(failed.get("actor_user_id")).isNull();
        assertThat(jdbc.queryForMap("select status, failed_attempts from trial_intent where business_id = ?", businessId))
                .containsEntry("status", "PENDING").containsEntry("failed_attempts", 1); // kept for the daily retry
    }

    @Test
    void ac2EvenWhenTheFailureCannotBeAuditedTheRegistrationSucceeds() throws Exception {
        doThrow(new IllegalStateException("audit store unavailable")).when(audit)
                .success(eq(TrialService.STARTED), any(), any(), any(), any(), any());
        doThrow(new IllegalStateException("audit store unavailable")).when(audit)
                .failure(eq(TrialService.START_FAILED), any(), any(), any(), any(), any());

        register(mockMvc, "trial-audit-fails").andExpect(status().isCreated());

        String businessId = businessOf(jdbc, "trial-audit-fails");
        assertThat(count("select count(*) from business_subscription")).isZero();
        assertThat(count("select count(*) from trial_intent where business_id = ? and status = 'PENDING' and failed_attempts = 1", businessId)).isEqualTo(1);
    }

    @Test
    void ac2ARegistrationThatRollsBackGetsNoTrial() throws Exception {
        // fails after the trial was handed over, before the registration commits
        doThrow(new IllegalStateException("mail queue unavailable")).when(emailVerification).sendAfterCommit(anyString(), anyString());

        register(mockMvc, "registration-fails").andExpect(status().isInternalServerError());

        assertThat(count("select count(*) from users where email like 'registration-fails-%'")).isZero();
        assertThat(count("select count(*) from business where custom_subdomain like 'registration-fails-%'")).isZero();
        assertThat(count("select count(*) from business_subscription")).isZero();
        assertThat(count("select count(*) from admin_audit_event where action like 'TRIAL%'")).isZero();
        assertThat(count("select count(*) from trial_intent")).isZero();
    }
}
