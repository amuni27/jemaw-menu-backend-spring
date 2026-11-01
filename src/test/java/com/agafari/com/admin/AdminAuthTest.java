package com.agafari.com.admin;

import com.agafari.com.enums.UserStatus;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory;
import com.agafari.com.support.TestDataFactory.Tenant;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AGF E4-M1 (AC5, AC6): Super Admin sign-in at /api/admin/auth/login. Only ACTIVE admins succeed; a wrong password, an
 * unknown email and an owner account get the same answer; inactive admins are told only after a correct password; the
 * S1-19 limits apply; admin tokens are short-lived. The owner login is unchanged.
 */
class AdminAuthTest extends AbstractIntegrationTest {

    @Autowired private UserRepository users;

    private ResultActions adminLogin(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    @Test
    void ac5AnActiveAdminSignsInAndGetsATokenWithoutABusiness() throws Exception {
        User admin = data.admin("signin");
        String body = adminLogin(admin.getEmail(), TestDataFactory.PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.admin.id").value(admin.getId()))
                .andExpect(jsonPath("$.admin.email").value(admin.getEmail()))
                .andReturn().getResponse().getContentAsString();
        String payload = new String(Base64.getUrlDecoder().decode(((String) JsonPath.read(body, "$.token")).split("\\.")[1]), StandardCharsets.UTF_8);
        java.util.Map<String, Object> claims = JsonPath.read(payload, "$");
        assertThat(claims.get("businessId")).isNull(); // absent or null: an admin has no business
        assertThat(claims.get("role")).isEqualTo("ADMIN");
    }

    @Test
    void ac5WrongPasswordUnknownEmailAndOwnerAccountsGetTheSameAnswer() throws Exception {
        User admin = data.admin("same-answer");
        Tenant owner = data.tenant("not-an-admin");
        for (String[] attempt : new String[][]{
                {admin.getEmail(), "Wrong-Passw0rd!"},
                {"nobody@admin.agafari.local", TestDataFactory.PASSWORD},
                {owner.owner().getEmail(), TestDataFactory.PASSWORD}}) {
            adminLogin(attempt[0], attempt[1])
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Invalid credentials"))
                    .andExpect(jsonPath("$.token").doesNotExist());
        }
    }

    @Test
    void ac5AnInactiveAdminIsToldOnlyAfterACorrectPassword() throws Exception {
        User admin = data.admin("inactive");
        admin.setStatus(UserStatus.SUSPENDED);
        users.save(admin);
        adminLogin(admin.getEmail(), "Wrong-Passw0rd!").andExpect(status().isBadRequest());
        adminLogin(admin.getEmail(), TestDataFactory.PASSWORD)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"));
    }

    @Test
    void ac5TheS119LoginLimitsApply() throws Exception {
        User admin = data.admin("limited");
        for (int i = 0; i < 5; i++) adminLogin(admin.getEmail(), "Wrong-Passw0rd!").andExpect(status().isBadRequest());
        adminLogin(admin.getEmail(), TestDataFactory.PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    void ac6AdminTokensExpireAfterThirtyMinutes() throws Exception {
        User admin = data.admin("ttl");
        String body = adminLogin(admin.getEmail(), TestDataFactory.PASSWORD).andReturn().getResponse().getContentAsString();
        String payload = new String(Base64.getUrlDecoder().decode(((String) JsonPath.read(body, "$.token")).split("\\.")[1]), StandardCharsets.UTF_8);
        long iat = ((Number) JsonPath.read(payload, "$.iat")).longValue();
        long exp = ((Number) JsonPath.read(payload, "$.exp")).longValue();
        assertThat(exp - iat).isEqualTo(30 * 60);
    }

    @Test
    void theOwnerLoginStillRefusesAnAdminAsBefore() throws Exception {
        User admin = data.admin("owner-door");
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + admin.getEmail() + "\",\"password\":\"" + TestDataFactory.PASSWORD + "\"}"))
                .andExpect(status().isForbidden());
    }
}
