package com.agafari.com.security;

import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S2-13A: "Forgot password?" sends a signed, 30-minute, single-use link through the mailer adapter; the same
 * response for every email; a compliant new password replaces the old one; rate limits; nothing secret in logs.
 */
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetTest extends AbstractIntegrationTest {

    private static final String LINK_BASE = "http://frontend.test.invalid/auth/reset-password";
    private static final String NEW_PASSWORD = "N3w-Secure-Pass!";

    @MockitoBean private PasswordResetMailer mailer;
    @Autowired private PasswordResetTokens tokens;

    private Tenant owner;

    @BeforeEach
    void setUp() {
        owner = data.tenant("alpha");
    }

    @AfterEach
    void restoreClock() {
        tokens.setClock(Clock.systemUTC());
    }

    private MvcResult forgot(String email, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/forgot-password").with(r -> { r.setRemoteAddr(ip); return r; })
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"))
                .andReturn();
    }

    /** Requests a reset for the owner and returns the token from the link given to the mailer. */
    private String requestToken() throws Exception {
        reset(mailer);
        assertThat(forgot(owner.owner().getEmail(), "198.51.100.1").getResponse().getStatus()).isEqualTo(202);
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(mailer, timeout(3000)).sendResetLink(eq(owner.owner().getEmail()), url.capture());
        assertThat(url.getValue()).startsWith(LINK_BASE + "#token=");
        return url.getValue().substring((LINK_BASE + "#token=").length());
    }

    private int resetWith(String token, String password) throws Exception {
        return resetResult(token, password, "198.51.100.2").getResponse().getStatus();
    }

    private MvcResult resetResult(String token, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/reset-password").with(r -> { r.setRemoteAddr(ip); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + password + "\"}"))
                .andReturn();
    }

    private int login(String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + owner.owner().getEmail() + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    // ------------------------------------------------------------------ request

    @Test
    void registeredAndUnknownEmailsGetTheSameResponse() throws Exception {
        MvcResult known = forgot(owner.owner().getEmail(), "198.51.100.10");
        MvcResult unknown = forgot("nobody@test.agafari.local", "198.51.100.11");

        assertThat(known.getResponse().getStatus()).isEqualTo(202);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(202);
        assertThat(unknown.getResponse().getContentAsString()).isEqualTo(known.getResponse().getContentAsString());
        assertThat(unknown.getResponse().getContentType()).isEqualTo(known.getResponse().getContentType());
        assertThat(JsonPath.<String>read(known.getResponse().getContentAsString(), "$.message"))
                .isEqualTo("If an account exists for that email, we've sent a link to reset the password. The link expires in 30 minutes.");

        verify(mailer, timeout(3000)).sendResetLink(eq(owner.owner().getEmail()), anyString());
        verify(mailer, after(300).never()).sendResetLink(eq("nobody@test.agafari.local"), anyString());
    }

    @Test
    void theResponseDoesNotWaitForEmailDelivery() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> { release.await(5, TimeUnit.SECONDS); return null; }).when(mailer).sendResetLink(anyString(), anyString());

        long start = System.nanoTime();
        MvcResult result = forgot(owner.owner().getEmail(), "198.51.100.12");
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();
        release.countDown();

        // delivery runs in the background, so a registered email is not slower to answer than an unknown one
        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(elapsedMs).isLessThan(2000);
    }

    // ------------------------------------------------------------------ reset

    @Test
    void aValidLinkSetsTheNewPassword() throws Exception {
        String sessionBefore = data.bearer(owner);
        String token = requestToken();

        mockMvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Your password has been changed. Sign in with your new password."));

        assertThat(login(NEW_PASSWORD)).isEqualTo(200);
        assertThat(login(TestDataFactory.PASSWORD)).isEqualTo(400);
        // documented: existing sessions stay valid until they expire
        mockMvc.perform(get("/api/auth/me").header("Authorization", sessionBefore)).andExpect(status().isOk());
    }

    @Test
    void aLinkCannotBeUsedTwice() throws Exception {
        String token = requestToken();
        assertThat(resetWith(token, NEW_PASSWORD)).isEqualTo(200);

        mockMvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"An0ther-Pass!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_LINK_INVALID"))
                .andExpect(jsonPath("$.message").value("This reset link is invalid or has expired. Request a new link."));
        assertThat(login(NEW_PASSWORD)).isEqualTo(200);
    }

    @Test
    void usingOneLinkInvalidatesEveryEarlierLink() throws Exception {
        String first = requestToken();
        String second = requestToken();
        assertThat(resetWith(second, NEW_PASSWORD)).isEqualTo(200);
        assertThat(resetWith(first, "An0ther-Pass!")).isEqualTo(400);
    }

    @Test
    void changingThePasswordOnTheAccountPageInvalidatesTheLink() throws Exception {
        String token = requestToken();
        mockMvc.perform(post("/api/auth/change-password").header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + TestDataFactory.PASSWORD + "\",\"newPassword\":\"Acc0unt-Page-Pass!\"}"))
                .andExpect(status().isOk());

        assertThat(resetWith(token, NEW_PASSWORD)).isEqualTo(400);
        assertThat(login("Acc0unt-Page-Pass!")).isEqualTo(200);
    }

    @Test
    void expiredLinksAreRejected() throws Exception {
        String token = requestToken();
        tokens.setClock(Clock.fixed(Instant.now().plus(Duration.ofMinutes(31)), ZoneOffset.UTC));
        assertThat(resetWith(token, NEW_PASSWORD)).isEqualTo(400);
        assertThat(login(TestDataFactory.PASSWORD)).isEqualTo(200);
    }

    @Test
    void tamperedLinksAreRejected() throws Exception {
        String token = requestToken();
        String[] parts = token.split("\\.");
        for (String bad : new String[]{parts[0] + "." + parts[1].substring(2), parts[1] + "." + parts[0], "not-a-token", token + "A"}) {
            mockMvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"" + bad + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("RESET_LINK_INVALID"));
        }
        assertThat(login(TestDataFactory.PASSWORD)).isEqualTo(200);
    }

    @Test
    void theNewPasswordMustMeetThePolicyAndAFailedAttemptKeepsTheLink() throws Exception {
        String token = requestToken();
        mockMvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"weak\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("newPassword")));
        assertThat(resetWith(token, NEW_PASSWORD)).isEqualTo(200);
    }

    @Test
    void concurrentSubmissionsOfOneLinkChangeThePasswordOnce() throws Exception {
        String token = requestToken();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String password = "Rac3-Pass-" + i + "!";
                results.add(pool.submit(() -> resetWith(token, password)));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) statuses.add(f.get(20, TimeUnit.SECONDS));
            assertThat(statuses).filteredOn(s -> s == 200).hasSize(1);
            assertThat(statuses).filteredOn(s -> s == 400).hasSize(3);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRequestsAllGetTheSameAnswer() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<MvcResult>> results = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                String ip = "198.51.100." + (40 + i);
                results.add(pool.submit(() -> forgot(owner.owner().getEmail(), ip)));
            }
            for (Future<MvcResult> f : results) assertThat(f.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(202);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ token cannot authenticate

    @Test
    void aResetTokenIsNotAnAccessToken() throws Exception {
        String token = requestToken();
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/menus").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ rate limits (S1-19)

    @Test
    void requestsAreLimitedPerEmailForRegisteredAndUnknownEmailsAlike() throws Exception {
        for (String email : new String[]{owner.owner().getEmail(), "nobody@test.agafari.local"}) {
            for (int i = 0; i < 3; i++) assertThat(forgot(email, "198.51.100." + (60 + i)).getResponse().getStatus()).isEqualTo(202);
            mockMvc.perform(post("/api/auth/forgot-password").with(r -> { r.setRemoteAddr("198.51.100.70"); return r; })
                            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email.toUpperCase() + "\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"))
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        }
    }

    @Test
    void requestsAreLimitedPerIp() throws Exception {
        for (int i = 0; i < 10; i++) assertThat(forgot("someone" + i + "@test.agafari.local", "203.0.113.9").getResponse().getStatus()).isEqualTo(202);
        assertThat(forgot("someone-else@test.agafari.local", "203.0.113.9").getResponse().getStatus()).isEqualTo(429);
        assertThat(forgot("someone-else@test.agafari.local", "203.0.113.10").getResponse().getStatus()).isEqualTo(202);
    }

    @Test
    void resetAttemptsAreLimitedPerIp() throws Exception {
        for (int i = 0; i < 10; i++) assertThat(resetResult("bad-token-" + i, NEW_PASSWORD, "203.0.113.20").getResponse().getStatus()).isEqualTo(400);
        assertThat(resetResult("bad-token-x", NEW_PASSWORD, "203.0.113.20").getResponse().getStatus()).isEqualTo(429);
    }

    // ------------------------------------------------------------------ logging (S1-16)

    @Test
    void neitherTokensLinksPasswordsNorFullEmailsAreLogged(CapturedOutput output) throws Exception {
        String token = requestToken();
        assertThat(resetWith(token, NEW_PASSWORD)).isEqualTo(200);
        assertThat(resetWith(token, NEW_PASSWORD)).isEqualTo(400);
        forgot("nobody@test.agafari.local", "198.51.100.90");

        String logs = output.getAll();
        assertThat(logs).doesNotContain(token, token.split("\\.")[1], "#token=", LINK_BASE, NEW_PASSWORD,
                owner.owner().getEmail(), "nobody@test.agafari.local");
        assertThat(logs).contains("event=password_reset.requested").contains("event=password_reset.completed");
    }

    @Test
    void noLinkIsSentWithoutARegisteredEmail() throws Exception {
        forgot("nobody@test.agafari.local", "198.51.100.91");
        verify(mailer, after(300).never()).sendResetLink(anyString(), anyString());
    }
}
