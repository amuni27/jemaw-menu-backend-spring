package com.agafari.com.security;

import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF-S1-19: authentication abuse protection (login, registration and change-password rate limits). */
@ExtendWith(OutputCaptureExtension.class)
class AuthRateLimitTest extends AbstractIntegrationTest {

    @Autowired private AuthRateLimiter rateLimiter;

    /** Test clock that only moves when told to. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-18T12:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void useTestClock() {
        rateLimiter.setClock(clock);
    }

    @AfterEach
    void restoreClock() {
        rateLimiter.setClock(Clock.systemUTC());
    }

    private static MockHttpServletRequestBuilder from(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mockMvc.perform(from(post("/api/auth/login"), ip).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private static void assertRateLimited(ResultActions result) throws Exception {
        result.andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Too many attempts. Please wait a few minutes and try again."))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void sixthAttemptAfterFiveFailuresForOneEmailIsRateLimitedEvenWithTheRightPassword() throws Exception {
        String email = data.tenant("alpha").owner().getEmail();
        for (int i = 0; i < 5; i++) {
            login(email, "wrong-password", "10.0.0." + (i + 1)).andExpect(status().isBadRequest()); // different IPs: per-email rule
        }

        assertRateLimited(login(email, TestDataFactory.PASSWORD, "10.0.0.9"));
        assertRateLimited(login(email.toUpperCase(), TestDataFactory.PASSWORD, "10.0.0.9")); // same email, any case
    }

    @Test
    void afterTheWindowExpiresTheCorrectPasswordWorksAgain() throws Exception {
        String email = data.tenant("alpha").owner().getEmail();
        for (int i = 0; i < 5; i++) login(email, "wrong-password", "10.0.0.1");
        assertRateLimited(login(email, TestDataFactory.PASSWORD, "10.0.0.1"));

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        login(email, TestDataFactory.PASSWORD, "10.0.0.1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void normalUsageIsNeverLimited() throws Exception {
        String email = data.tenant("alpha").owner().getEmail();
        for (int i = 0; i < 25; i++) {
            login(email, TestDataFactory.PASSWORD, "10.0.0.1").andExpect(status().isOk());
        }
    }

    @Test
    void aSuccessfulLoginResetsTheEmailFailureCount() throws Exception {
        String email = data.tenant("alpha").owner().getEmail();
        for (int i = 0; i < 4; i++) login(email, "wrong-password", "10.0.0.1").andExpect(status().isBadRequest());
        login(email, TestDataFactory.PASSWORD, "10.0.0.1").andExpect(status().isOk());
        for (int i = 0; i < 4; i++) login(email, "wrong-password", "10.0.0.1").andExpect(status().isBadRequest());

        login(email, TestDataFactory.PASSWORD, "10.0.0.1").andExpect(status().isOk());
    }

    @Test
    void unknownEmailsAreLimitedTheSameWayAsKnownOnes() throws Exception {
        for (int i = 0; i < 5; i++) login("nobody@test.agafari.local", "whatever", "10.0.0.1").andExpect(status().isBadRequest());
        assertRateLimited(login("nobody@test.agafari.local", "whatever", "10.0.0.1"));
    }

    @Test
    void oneIpTryingManyEmailsIsLimitedWithoutAffectingOtherIps() throws Exception {
        for (int i = 0; i < 20; i++) {
            login("victim" + i + "@test.agafari.local", "guess", "203.0.113.7").andExpect(status().isBadRequest());
        }
        assertRateLimited(login("victim99@test.agafari.local", "guess", "203.0.113.7"));

        String email = data.tenant("alpha").owner().getEmail();
        login(email, TestDataFactory.PASSWORD, "198.51.100.4").andExpect(status().isOk());
    }

    private ResultActions register(int n, String ip) throws Exception {
        String body = """
                {"fullName":"Owner %1$d","email":"owner%1$d@test.agafari.local","password":"Str0ng!Pass","businessName":"Cafe %1$d",
                 "businessPhone":"+251911000%1$03d","streetAddress":"1 Test St","city":"Addis Ababa","state":"Addis Ababa",
                 "zipcode":"","customSubdomain":"cafe-%1$d","open24_7":true,
                 "locationUrl":"https://maps.app.goo.gl/TestCafe1"}""".formatted(n);
        return mockMvc.perform(from(post("/api/auth/register"), ip).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void registrationsFromOneIpAreLimitedPerHour() throws Exception {
        for (int i = 0; i < 10; i++) register(i, "203.0.113.20").andExpect(status().isCreated());

        assertRateLimited(register(10, "203.0.113.20"));
        register(11, "198.51.100.30").andExpect(status().isCreated()); // other IPs unaffected

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        register(12, "203.0.113.20").andExpect(status().isCreated());
    }

    @Test
    void changePasswordAttemptsFromOneIpAreLimited() throws Exception {
        var owner = data.tenant("alpha");
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(from(post("/api/auth/change-password"), "203.0.113.40").header("Authorization", data.bearer(owner))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"wrong\",\"newPassword\":\"N3w-Passw0rd\"}"))
                    .andExpect(status().isBadRequest());
        }
        assertRateLimited(mockMvc.perform(from(post("/api/auth/change-password"), "203.0.113.40").header("Authorization", data.bearer(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + TestDataFactory.PASSWORD + "\",\"newPassword\":\"N3w-Passw0rd\"}")));
    }

    @Test
    void rateLimitEventsAreLoggedWithMaskedIdentifiers(CapturedOutput output) throws Exception {
        String email = "masked.target@test.agafari.local";
        for (int i = 0; i < 6; i++) login(email, "wrong", "198.51.100.12");                           // per-email rule
        for (int i = 0; i < 21; i++) login("probe" + i + "@test.agafari.local", "wrong", "203.0.113.55"); // per-IP rule

        assertThat(output.getAll()).contains("event=auth.rate_limited rule=login_email key=m***@test.agafari.local",
                "event=auth.rate_limited rule=login_ip key=203.0.113.x");
        assertThat(output.getAll()).doesNotContain(email, "203.0.113.55");
    }
}
