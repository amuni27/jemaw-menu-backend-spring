package com.agafari.com.verification;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.security.EmailVerificationService;
import com.agafari.com.security.EmailVerificationTokens;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.RecordingEmailVerificationMailer;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-22 (option B, proposal §11): email verification with a publish gate. Registration and sign-in are unchanged;
 * the gate applies only to publishing a menu, only while the enforcement flag is on AND verification is operational.
 * Verification is a single conditional update, so a link completes it at most once, even concurrently.
 */
@ExtendWith(OutputCaptureExtension.class)
class EmailVerificationTest extends AbstractIntegrationTest {

    @Autowired private RecordingEmailVerificationMailer mailer;
    @Autowired private EmailVerificationService verification;
    @Autowired private EmailVerificationTokens tokens;
    @Autowired private UserRepository users;

    @AfterEach
    void restore() {
        mailer.reset();
        verification.setEnforcePublishGate(false);
        tokens.setClock(Clock.systemUTC());
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions register(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Verify Owner","email":"%s","password":"Str0ng!Pass","businessName":"Verify Cafe",
                 "businessPhone":"+251911%06d","customSubdomain":"%s","open24_7":true,"country":"ET",
                 "locationUrl":"https://maps.app.goo.gl/AbC123xyz"}"""
                .formatted(email, Math.abs(email.hashCode()) % 1_000_000, email.split("@")[0])));
    }

    private List<Map.Entry<String, String>> awaitSent(int count) throws InterruptedException {
        for (int i = 0; i < 60 && mailer.sent().size() < count; i++) Thread.sleep(50);
        return mailer.sent();
    }

    private static String tokenOf(String link) {
        return link.substring(link.indexOf("#token=") + "#token=".length());
    }

    private ResultActions verify(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"));
    }

    private ResultActions resend(Tenant t) throws Exception {
        return mockMvc.perform(post("/api/auth/verify-email/resend").header("Authorization", data.bearer(t)));
    }

    private ResultActions createMenu(Tenant t, boolean active, String visibility) throws Exception {
        return mockMvc.perform(post("/api/menus").header("Authorization", data.bearer(t)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Lunch\",\"menuTypeId\":\"mt-lunch\",\"isActive\":" + active + ",\"visibility\":\"" + visibility + "\"}"));
    }

    private ResultActions patchMenu(Tenant t, String menuId, String json) throws Exception {
        return mockMvc.perform(patch("/api/menus/{id}", menuId).header("Authorization", data.bearer(t))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private User reload(Tenant t) {
        return users.findById(t.owner().getId()).orElseThrow();
    }

    // ---------------------------------------------------------------- AC1: registration

    @Test
    void ac1RegistrationSignsInAsBeforeAndSendsOneLinkToTheNewAddress() throws Exception {
        register("new-owner@verify.test").andExpect(status().isCreated()).andExpect(jsonPath("$.token").isNotEmpty());

        List<Map.Entry<String, String>> sent = awaitSent(1);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getKey()).isEqualTo("new-owner@verify.test");
        assertThat(sent.get(0).getValue()).startsWith("http://frontend.test.invalid/auth/verify-email#token=");
        assertThat(users.findByEmail("new-owner@verify.test").orElseThrow().getEmailVerifiedAt()).isNull();
    }

    // ---------------------------------------------------------------- AC2: verify link, single use

    @Test
    void ac2AValidLinkVerifiesOnceAndAReplayReportsAlreadyVerified() throws Exception {
        Tenant t = data.tenant("ev-verify");
        String token = tokens.issue(t.owner().getId(), t.owner().getEmail());

        verify(token).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"));
        Instant first = reload(t).getEmailVerifiedAt();
        assertThat(first).isNotNull();

        verify(token).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ALREADY_VERIFIED"));
        assertThat(reload(t).getEmailVerifiedAt()).isEqualTo(first); // nothing changed
    }

    @Test
    void ac2TamperedExpiredForeignAndMissingLinksFailWithOneGenericMessage() throws Exception {
        Tenant t = data.tenant("ev-bad");
        String token = tokens.issue(t.owner().getId(), t.owner().getEmail());
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");

        tokens.setClock(Clock.fixed(Instant.now().plus(Duration.ofHours(25)), ZoneOffset.UTC));
        verify(token).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VERIFY_LINK_INVALID"));
        tokens.setClock(Clock.systemUTC());

        for (String bad : List.of(tampered, "not-a-token", "")) {
            verify(bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VERIFY_LINK_INVALID"));
        }
        assertThat(reload(t).getEmailVerifiedAt()).isNull();
    }

    @Test
    void ac2SimultaneousVerificationsOfOneLinkChangeTheAccountExactlyOnce() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 10; round++) {
                Tenant t = data.tenant("ev-race-" + round);
                String token = tokens.issue(t.owner().getId(), t.owner().getEmail());
                CyclicBarrier start = new CyclicBarrier(threads);
                List<Future<EmailVerificationService.Outcome>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    results.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        return verification.verify(token);
                    }));
                }
                List<EmailVerificationService.Outcome> outcomes = new ArrayList<>();
                for (Future<EmailVerificationService.Outcome> f : results) outcomes.add(f.get(30, TimeUnit.SECONDS));
                assertThat(outcomes).as("round %d", round)
                        .containsOnlyOnce(EmailVerificationService.Outcome.VERIFIED)
                        .filteredOn(o -> o != EmailVerificationService.Outcome.VERIFIED)
                        .containsOnly(EmailVerificationService.Outcome.ALREADY_VERIFIED);
            }
        } finally {
            pool.shutdown();
        }
    }

    // ---------------------------------------------------------------- AC3: publish gate

    @Test
    void ac3WithTheFlagOnAnUnverifiedOwnerCanDoEverythingExceptPublish() throws Exception {
        verification.setEnforcePublishGate(true);
        Tenant t = data.tenant("ev-gate");

        createMenu(t, true, "PUBLIC").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"))
                .andExpect(jsonPath("$.message").value("Verify your email to publish menus."));

        String body = createMenu(t, false, "PUBLIC").andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String menuId = JsonPath.read(body, "$.id");
        patchMenu(t, menuId, "{\"name\":\"Lunch v2\"}").andExpect(status().isOk());                 // edit draft
        patchMenu(t, menuId, "{\"isActive\":true}").andExpect(status().isForbidden());               // publish
        patchMenu(t, menuId, "{\"isActive\":true,\"visibility\":\"PRIVATE\"}").andExpect(status().isOk()); // active but private
        patchMenu(t, menuId, "{\"visibility\":\"PUBLIC\"}").andExpect(status().isForbidden());       // becomes public
        mockMvc.perform(get("/api/menus/{id}/preview", menuId).header("Authorization", data.bearer(t))).andExpect(status().isOk());
        mockMvc.perform(post("/api/menus/{id}/duplicate", menuId).header("Authorization", data.bearer(t))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isCreated());

        verify(tokens.issue(t.owner().getId(), t.owner().getEmail())).andExpect(status().isOk());
        patchMenu(t, menuId, "{\"visibility\":\"PUBLIC\"}").andExpect(status().isOk());              // verified: publishes
    }

    @Test
    void ac3EditingAMenuThatIsAlreadyPublishedIsNotBlocked() throws Exception {
        Tenant t = data.tenant("ev-live");
        var live = data.menu(t, "Already live"); // active + public, published before the gate
        verification.setEnforcePublishGate(true);
        patchMenu(t, live.getId(), "{\"name\":\"Renamed\"}").andExpect(status().isOk());
    }

    @Test
    void ac3WithTheFlagOffPublishingIsUnchanged() throws Exception {
        Tenant t = data.tenant("ev-flag-off");
        createMenu(t, true, "PUBLIC").andExpect(status().isCreated());
    }

    // ---------------------------------------------------------------- AC4: email change

    @Test
    void ac4ChangingTheEmailUnverifiesSendsANewLinkAndKillsOldLinks() throws Exception {
        Tenant t = data.tenant("ev-change");
        String oldLink = tokens.issue(t.owner().getId(), t.owner().getEmail());
        verify(oldLink).andExpect(status().isOk());

        mockMvc.perform(patch("/api/user").header("Authorization", data.bearer(t)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"moved@verify.test\",\"currentPassword\":\"Test-Passw0rd!\"}"))
                .andExpect(status().isOk());

        assertThat(reload(t).getEmailVerifiedAt()).isNull();
        List<Map.Entry<String, String>> sent = awaitSent(1);
        assertThat(sent).extracting(Map.Entry::getKey).containsExactly("moved@verify.test");
        verify(oldLink).andExpect(status().isBadRequest()); // issued for the old address
        verify(tokenOf(sent.get(0).getValue())).andExpect(jsonPath("$.status").value("VERIFIED"));
    }

    // ---------------------------------------------------------------- AC5: resend

    @Test
    void ac5ResendSendsALinkAndAnswersHonestlyWhenDeliveryFails() throws Exception {
        Tenant t = data.tenant("ev-resend");
        resend(t).andExpect(status().isAccepted()).andExpect(jsonPath("$.message").value(
                "We sent a new link to ev-resend@test.agafari.local. It can take a few minutes; check your spam folder."));
        assertThat(mailer.sent()).hasSize(1);

        mailer.setFailing(true);
        for (int i = 0; i < 4; i++) { // failed sends don't use up the 3-per-hour allowance
            resend(t).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("EMAIL_DELIVERY_FAILED"))
                    .andExpect(jsonPath("$.message").value("We couldn't send the email right now. Please try again in a few minutes."));
        }
        mailer.setFailing(false);
        resend(t).andExpect(status().isAccepted());
        resend(t).andExpect(status().isAccepted());
        resend(t).andExpect(status().isTooManyRequests()); // 3 successful sends per hour per owner
    }

    @Test
    void ac5ResendForAVerifiedOwnerSendsNothing() throws Exception {
        Tenant t = data.tenant("ev-resend-done");
        verify(tokens.issue(t.owner().getId(), t.owner().getEmail()));
        resend(t).andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Your email is already verified."));
        assertThat(mailer.sent()).isEmpty();
    }

    @Test
    void ac5ResendNeedsASignedInOwner() throws Exception {
        mockMvc.perform(post("/api/auth/verify-email/resend")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- AC6: verification not operational

    @Test
    void ac6WhenVerificationIsNotOperationalNothingChangesEvenWithTheFlagOn() throws Exception {
        mailer.setOperational(false);
        verification.setEnforcePublishGate(true);

        register("quiet@verify.test").andExpect(status().isCreated());
        Thread.sleep(300);
        assertThat(mailer.sent()).isEmpty();

        Tenant t = data.tenant("ev-off");
        createMenu(t, true, "PUBLIC").andExpect(status().isCreated()); // no gate (fail open)
        resend(t).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_UNAVAILABLE"));
        mockMvc.perform(get("/api/auth/me").header("Authorization", data.bearer(t)))
                .andExpect(jsonPath("$.user.emailVerification.available").value(false))
                .andExpect(jsonPath("$.user.emailVerification.publishGateEnabled").value(false));
    }

    // ---------------------------------------------------------------- AC8: no link or token in logs

    @Test
    void ac8LinksAndTokensAreNeverLoggedAndEmailsAreMasked(CapturedOutput output) throws Exception {
        register("logcheck@verify.test").andExpect(status().isCreated());
        String link = awaitSent(1).get(0).getValue();
        Tenant t = data.tenant("ev-logs");
        resend(t).andExpect(status().isAccepted());
        verify(tokenOf(link)).andExpect(status().isOk());

        assertThat(output.getAll()).doesNotContain(tokenOf(link)).doesNotContain("#token=")
                .doesNotContain("logcheck@verify.test");
        assertThat(output.getAll()).contains("event=email_verification.sent");
    }

    // ---------------------------------------------------------------- AC9: me reports the state

    @Test
    void ac9MeReportsVerifiedAvailableAndWhetherTheGateIsOn() throws Exception {
        Tenant t = data.tenant("ev-me");
        mockMvc.perform(get("/api/auth/me").header("Authorization", data.bearer(t)))
                .andExpect(jsonPath("$.user.emailVerification.verified").value(false))
                .andExpect(jsonPath("$.user.emailVerification.available").value(true))
                .andExpect(jsonPath("$.user.emailVerification.publishGateEnabled").value(false));

        verification.setEnforcePublishGate(true);
        verify(tokens.issue(t.owner().getId(), t.owner().getEmail()));
        mockMvc.perform(get("/api/auth/me").header("Authorization", data.bearer(t)))
                .andExpect(jsonPath("$.user.emailVerification.verified").value(true))
                .andExpect(jsonPath("$.user.emailVerification.publishGateEnabled").value(true));
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ev-me@test.agafari.local\",\"password\":\"Test-Passw0rd!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.emailVerification.verified").value(true));
    }
}
