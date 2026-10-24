package com.agafari.com.security;

import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-13A: without a valid APP_PASSWORD_RESET_SECRET the feature is off and fails safe, and the default mailer
 * (the only one in the production artifact until D5) sends nothing.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "app.password-reset.secret=")
class PasswordResetDisabledTest extends AbstractIntegrationTest {

    @Autowired private ApplicationContext context;

    @Test
    void requestsGetTheUsualAnswerButNothingIsSent(CapturedOutput output) throws Exception {
        var owner = data.tenant("alpha");
        mockMvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + owner.owner().getEmail() + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("If an account exists for that email, we've sent a link to reset the password. The link expires in 30 minutes."));

        assertThat(context.getBean(PasswordResetTokens.class).enabled()).isFalse();
        assertThat(output.getAll()).contains("event=password_reset.unavailable").doesNotContain(owner.owner().getEmail());
    }

    @Test
    void resetSubmissionsSayTheFeatureIsUnavailable() throws Exception {
        mockMvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"x.y\",\"newPassword\":\"N3w-Secure-Pass!\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RESET_UNAVAILABLE"));
    }

    @Test
    void theProductionMailerSendsNothing() {
        assertThat(context.getBean(PasswordResetMailer.class)).isInstanceOf(DisabledPasswordResetMailer.class);
    }
}
