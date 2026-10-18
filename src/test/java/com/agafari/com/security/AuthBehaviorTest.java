package com.agafari.com.security;

import com.agafari.com.enums.UserRole;
import com.agafari.com.enums.UserStatus;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.service.JwtService;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory;
import com.agafari.com.support.TestDataFactory.Tenant;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF-S1-07: 401 for missing, invalid, expired and orphaned tokens; 403 for forbidden roles and
 * non-ACTIVE accounts (at login and per request); a new token when the email changes.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuthBehaviorTest extends AbstractIntegrationTest {

    @Autowired private JwtService jwtService;
    @Autowired private UserRepository users;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    // ---------------------------------------------------------------------------------------
    // 401: missing, expired, tampered, wrong-signature, malformed, orphaned tokens
    // ---------------------------------------------------------------------------------------

    @Test
    void missingTokenReturns401WithJsonBody() throws Exception {
        mockMvc.perform(get("/api/menus"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.message").value("Authentication required"));
    }

    @Test
    void missingTokenOnBusinessEndpointReturns401() throws Exception {
        Tenant owner = data.tenant("alpha");
        mockMvc.perform(get("/api/business/{id}/hours", owner.businessId()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void expiredTokenReturns401WithoutStackTraceOrTokenInLogs(CapturedOutput output) throws Exception {
        Tenant owner = data.tenant("alpha");
        String expired = Jwts.builder()
                .subject(owner.owner().getEmail())
                .issuedAt(Date.from(Instant.now().minus(3, ChronoUnit.HOURS)))
                .expiration(Date.from(Instant.now().minus(1, ChronoUnit.HOURS)))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(get("/api/menus").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        assertThat(output.getAll()).contains("event=auth.token_rejected");
        assertNoTokenOrStackTrace(output, expired);
    }

    @Test
    void wrongSignatureTokenReturns401(CapturedOutput output) throws Exception {
        Tenant owner = data.tenant("alpha");
        String forged = Jwts.builder()
                .subject(owner.owner().getEmail())
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(Keys.hmacShaKeyFor("a-completely-different-secret-0123456789".getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(get("/api/menus").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        assertNoTokenOrStackTrace(output, forged);
    }

    @Test
    void tamperedTokenReturns401(CapturedOutput output) throws Exception {
        Tenant owner = data.tenant("alpha");
        String valid = data.bearer(owner).substring("Bearer ".length());
        String[] parts = valid.split("\\.");
        // Swap the payload for another user's claims while keeping the original signature
        String otherPayload = data.bearer(data.tenant("bravo")).substring("Bearer ".length()).split("\\.")[1];
        String tampered = parts[0] + "." + otherPayload + "." + parts[2];

        mockMvc.perform(get("/api/menus").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
        assertNoTokenOrStackTrace(output, tampered);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-jwt", "a.b.c", "Bearer", ""})
    void malformedTokenReturns401(String token, CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/menus").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        assertThat(output.getAll()).doesNotContain("\tat io.jsonwebtoken");
    }

    @Test
    void tokenForDeletedUserReturns401(CapturedOutput output) throws Exception {
        // A correctly signed token whose subject matches no user (deleted account)
        String orphan = jwtService.signToken("deleted@test.agafari.local", "gone-user", "gone-business", "VENUE");

        mockMvc.perform(get("/api/menus").header("Authorization", "Bearer " + orphan))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        assertNoTokenOrStackTrace(output, orphan);
    }

    // ---------------------------------------------------------------------------------------
    // Valid tokens and public endpoints are unaffected
    // ---------------------------------------------------------------------------------------

    @Test
    void activeOwnerWithValidTokenIsUnaffected() throws Exception {
        Tenant owner = data.tenant("alpha");
        mockMvc.perform(get("/api/menus").header("Authorization", data.bearer(owner))).andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(owner.owner().getEmail()));
    }

    @Test
    void loginStillIssuesAWorkingToken() throws Exception {
        Tenant owner = data.tenant("alpha");
        String token = login(owner.owner().getEmail(), TestDataFactory.PASSWORD);
        mockMvc.perform(get("/api/menus").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    @Test
    void publicEndpointsNeedNoTokenAndIgnoreBadOnes() throws Exception {
        Tenant owner = data.tenant("alpha");
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId())).andExpect(status().isOk());
        mockMvc.perform(get("/venue/{id}/menu", owner.businessId()).header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void badCredentialsAndDuplicatesStill400() throws Exception {
        Tenant owner = data.tenant("alpha");
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + owner.owner().getEmail() + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody@test.agafari.local\",\"password\":\"whatever\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    // ---------------------------------------------------------------------------------------
    // 403: forbidden role and non-ACTIVE accounts
    // ---------------------------------------------------------------------------------------

    @Test
    void forbiddenRoleReturns403FromAccessDeniedHandler() throws Exception {
        Tenant admin = data.tenant("admin", UserRole.ADMIN);
        mockMvc.perform(get("/api/menus").header("Authorization", data.bearer(admin)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "INACTIVE"})
    void existingTokenStopsWorkingWhenAccountBecomesInactive(UserStatus status) throws Exception {
        Tenant owner = data.tenant("alpha");
        String bearer = data.bearer(owner);
        mockMvc.perform(get("/api/menus").header("Authorization", bearer)).andExpect(status().isOk());

        setStatus(owner, status);

        for (String path : new String[]{"/api/menus", "/api/auth/me", "/api/business/" + owner.businessId() + "/hours"}) {
            mockMvc.perform(get(path).header("Authorization", bearer))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"))
                    .andExpect(jsonPath("$.message").value("Account is not active. Contact support."));
        }
    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"SUSPENDED", "INACTIVE"})
    void inactiveAccountCannotLogIn(UserStatus status) throws Exception {
        Tenant owner = data.tenant("alpha");
        setStatus(owner, status);

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + owner.owner().getEmail() + "\",\"password\":\"" + TestDataFactory.PASSWORD + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"))
                .andExpect(jsonPath("$.message").value("Account is not active. Contact support."))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(content().string(not(containsString("eyJ"))));
    }

    @Test
    void inactiveAccountWithWrongPasswordGetsNeutral400() throws Exception {
        // The account status is only revealed to someone who knows the password
        Tenant owner = data.tenant("alpha");
        setStatus(owner, UserStatus.SUSPENDED);

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + owner.owner().getEmail() + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    // ---------------------------------------------------------------------------------------
    // Email change: current password required, new token issued, old token rejected
    // ---------------------------------------------------------------------------------------

    @Test
    void emailChangeIssuesNewTokenAndRejectsTheOldOne() throws Exception {
        Tenant owner = data.tenant("alpha");
        String oldBearer = data.bearer(owner);

        MvcResult result = mockMvc.perform(patch("/api/user").header("Authorization", oldBearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alpha.new@test.agafari.local\",\"currentPassword\":\"" + TestDataFactory.PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alpha.new@test.agafari.local"))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();
        String newToken = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.token");

        mockMvc.perform(get("/api/menus").header("Authorization", oldBearer))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/menus").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + newToken))
                .andExpect(jsonPath("$.user.email").value("alpha.new@test.agafari.local"));
    }

    @Test
    void emailChangeRequiresCurrentPassword() throws Exception {
        Tenant owner = data.tenant("alpha");
        String bearer = data.bearer(owner);

        mockMvc.perform(patch("/api/user").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alpha.new@test.agafari.local\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Current password is required to change email"));
        mockMvc.perform(patch("/api/user").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"alpha.new@test.agafari.local\",\"currentPassword\":\"wrong-password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Current password is incorrect"));

        assertThat(users.findById(owner.owner().getId()).orElseThrow().getEmail()).isEqualTo(owner.owner().getEmail());
        mockMvc.perform(get("/api/menus").header("Authorization", bearer)).andExpect(status().isOk());
    }

    @Test
    void invalidEmailFormatIsRejected() throws Exception {
        Tenant owner = data.tenant("alpha");
        mockMvc.perform(patch("/api/user").header("Authorization", data.bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"currentPassword\":\"" + TestDataFactory.PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("email"));
        assertThat(users.findById(owner.owner().getId()).orElseThrow().getEmail()).isEqualTo(owner.owner().getEmail());
    }

    @Test
    void nameOrPhoneUpdateNeedsNoPasswordAndKeepsTheToken() throws Exception {
        Tenant owner = data.tenant("alpha");
        String bearer = data.bearer(owner);

        mockMvc.perform(patch("/api/user").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Alpha Renamed\",\"phoneNumber\":\"+251911222333\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Alpha Renamed"))
                .andExpect(jsonPath("$.token").doesNotExist());
        // Re-sending the unchanged email is not an email change
        mockMvc.perform(patch("/api/user").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + owner.owner().getEmail().toUpperCase() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").doesNotExist());

        mockMvc.perform(get("/api/menus").header("Authorization", bearer)).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------------------------------

    private void setStatus(Tenant tenant, UserStatus status) {
        var user = users.findById(tenant.owner().getId()).orElseThrow();
        user.setStatus(status);
        users.save(user);
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.token");
    }

    private static void assertNoTokenOrStackTrace(CapturedOutput output, String token) {
        assertThat(output.getAll()).doesNotContain(token);
        assertThat(output.getAll()).doesNotContain("\tat io.jsonwebtoken");
        assertThat(output.getAll()).doesNotContain("JWT auth failed");
    }
}
