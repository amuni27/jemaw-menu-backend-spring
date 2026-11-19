package com.agafari.com.trial;

import com.agafari.com.security.AdminAuditService;
import com.agafari.com.security.EmailVerificationService;
import com.agafari.com.service.TrialService;
import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static com.agafari.com.support.MonetizationFixtures.config;
import static com.agafari.com.trial.TrialTestSupport.businessOf;
import static com.agafari.com.trial.TrialTestSupport.register;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF BE-2 (register §45 D-3 B, §48), realigned from E4-M5's TrialRegistrationAfterCommitTest: registration creates the
 * owner, the restaurant and its business hours, and nothing else. With trials enabled under Setting 1 it no longer records
 * a trial intent or starts a trial (the owner starts one explicitly, BE-1/FE-1); under Setting 2 no subscription is
 * assigned. The registration contract and sign-in are unchanged, and a registration that rolls back leaves nothing.
 */
class RegistrationStartsNoTrialTest extends AbstractIntegrationTest {

    @MockitoSpyBean private AdminAuditService audit;
    @MockitoSpyBean private EmailVerificationService emailVerification;
    @Autowired private TrialService trials;
    @Autowired private JdbcTemplate jdbc;

    private String adminId;

    @BeforeEach
    void setUp() {
        adminId = data.admin("registration-no-trial").getId();
        config(jdbc, 1, "SETTING_1", false, false, false, adminId);
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

    private void nothingTrialRelated() {
        assertThat(count("select count(*) from business_subscription")).isZero();
        assertThat(count("select count(*) from trial_intent")).isZero();
        assertThat(count("select count(*) from admin_audit_event where action like 'TRIAL%'")).isZero();
        verify(audit, never()).success(eq(TrialService.STARTED), any(), any(), any(), any(), any());
        verify(audit, never()).failure(eq(TrialService.START_FAILED), any(), any(), any(), any(), any());
    }

    @Test
    void underSetting1ARegistrationCreatesOwnerRestaurantAndHoursButNoTrialAndTheOwnerCanSignIn() throws Exception {
        register(mockMvc, "no-trial").andExpect(status().isCreated()).andExpect(jsonPath("$.message").value("Account created"))
                .andExpect(jsonPath("$.token").isNotEmpty());

        String businessId = businessOf(jdbc, "no-trial");
        assertThat(count("select count(*) from users u join business b on b.owner_user_id = u.id where b.id = ? and u.role = 'VENUE'", businessId)).isEqualTo(1);
        assertThat(count("select count(*) from business_hours where business_id = ?", businessId)).isEqualTo(7);
        nothingTrialRelated();

        String email = jdbc.queryForObject("select u.email from users u join business b on b.owner_user_id = u.id where b.id = ?", String.class, businessId);
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Str0ng!Pass1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void registrationNoLongerDependsOnTheTrialMachinery() throws Exception {
        // before BE-2 these failures were absorbed by the after-commit trial step; now registration never reaches it
        doThrow(new IllegalStateException("audit store unavailable")).when(audit).success(eq(TrialService.STARTED), any(), any(), any(), any(), any());
        doThrow(new IllegalStateException("audit store unavailable")).when(audit).failure(eq(TrialService.START_FAILED), any(), any(), any(), any(), any());

        register(mockMvc, "trial-machinery-down").andExpect(status().isCreated());

        assertThat(count("select count(*) from business_hours where business_id = ?", businessOf(jdbc, "trial-machinery-down"))).isEqualTo(7);
        nothingTrialRelated();
    }

    @Test
    void underSetting2ARegistrationGetsNoSubscriptionAtAll() throws Exception {
        config(jdbc, 2, "SETTING_2", true, false, true, adminId);
        register(mockMvc, "setting2-none").andExpect(status().isCreated());
        assertThat(count("select count(*) from business_hours where business_id = ?", businessOf(jdbc, "setting2-none"))).isEqualTo(7);
        nothingTrialRelated();
    }

    @Test
    void aRegistrationThatRollsBackLeavesNothing() throws Exception {
        doThrow(new IllegalStateException("mail queue unavailable")).when(emailVerification).sendAfterCommit(anyString(), anyString());

        register(mockMvc, "registration-fails").andExpect(status().isInternalServerError());

        assertThat(count("select count(*) from users where email like 'registration-fails-%'")).isZero();
        assertThat(count("select count(*) from business where custom_subdomain like 'registration-fails-%'")).isZero();
        nothingTrialRelated();
    }
}
