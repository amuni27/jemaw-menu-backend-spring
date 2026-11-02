package com.agafari.com.selfservice;

import com.agafari.com.service.PlanRequestService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.RecordingEmailVerificationMailer;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import static com.agafari.com.selfservice.SelfServiceActivationTest.body;
import static com.agafari.com.support.MonetizationFixtures.config;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF Self-Service Phase A (SS-2): until email verification is operational (an email provider, D5), an unverified owner
 * can start Free with Ads; once it is operational, verification is required (configuration only, no code change).
 */
class SelfServiceBeforeD5Test extends AbstractIntegrationTest {

    @Autowired private RecordingEmailVerificationMailer mailer; // no provider = verification not operational
    @Autowired private PlanRequestService planRequests;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void reset() {
        planRequests.setOwnerPlansVisible(false);
        planRequests.setSelfServiceEnabled(false);
        mailer.setOperational(true);
    }

    @Test
    void ss2WithoutOperationalVerificationAnUnverifiedOwnerCanStartFree() throws Exception {
        mailer.setOperational(false); // no provider yet (production before D5)
        var admin = data.admin("ss-d5-admin");
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        planRequests.setOwnerPlansVisible(true);
        planRequests.setSelfServiceEnabled(true);
        Tenant owner = data.tenant("ss-d5-owner"); // email not verified
        mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(owner))).andExpect(jsonPath("$.selfService.verificationRequired").value(false));
        mockMvc.perform(post("/api/plan/activations").header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content(body("MODEL_2", 1, null, null))).andExpect(status().isCreated());
    }
}
