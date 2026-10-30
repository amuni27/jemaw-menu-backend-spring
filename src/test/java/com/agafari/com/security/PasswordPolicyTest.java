package com.agafari.com.security;

import com.agafari.com.enums.UserRole;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF-S1-17: password policy (8+ with upper, lower, number, symbol) on register and change-password; legacy logins keep working. */
class PasswordPolicyTest extends AbstractIntegrationTest {

    static final String POLICY_MESSAGE = "Password must be 8-72 characters and include an uppercase letter, a lowercase letter, a number and a symbol";

    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;

    private static String registration(String password) {
        return """
                {"fullName":"Policy Owner","email":"policy.owner@test.agafari.local","password":"%s","businessName":"Policy Cafe",
                 "businessPhone":"+251911000222","streetAddress":"1 Test St","city":"Addis Ababa","state":"Addis Ababa",
                 "zipcode":"","customSubdomain":"policy-cafe","open24_7":true,
                 "locationUrl":"https://maps.app.goo.gl/TestCafe1"}""".formatted(password);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abcdef", "abcdefgh", "Abcdefgh", "Abcdefg1", "ABCDEFG1!", "abcdefg1!", "Ab1!"})
    void registrationRejectsWeakPasswordsWithAFieldError(String weak) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(registration(weak)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(POLICY_MESSAGE));
    }

    @Test
    void registrationAcceptsACompliantPassword() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(registration("Str0ng!Pass")))
                .andExpect(status().isCreated());
    }

    @Test
    void overlongPasswordsAreA400NotAServerError() throws Exception {
        String longPassword = "Aa1!" + "x".repeat(80);
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(registration(longPassword)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));

        var owner = data.tenant("alpha");
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + owner.owner().getEmail() + "\",\"password\":\"" + longPassword + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid credentials"));
    }

    @Test
    void existingUserWithASixCharacterPasswordCanStillLogIn() throws Exception {
        var owner = data.tenant("legacy", UserRole.VENUE);
        var user = users.findById(owner.owner().getId()).orElseThrow();
        user.setPasswordHash(passwordEncoder.encode("abc123"));
        users.save(user);

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + owner.owner().getEmail() + "\",\"password\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void changePasswordEnforcesThePolicyOnTheNewPasswordOnly() throws Exception {
        var owner = data.tenant("alpha");
        var user = users.findById(owner.owner().getId()).orElseThrow();
        user.setPasswordHash(passwordEncoder.encode("abc123")); // legacy current password
        users.save(user);

        mockMvc.perform(post("/api/auth/change-password").header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"abc123\",\"newPassword\":\"newpass1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("newPassword"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(POLICY_MESSAGE));

        mockMvc.perform(post("/api/auth/change-password").header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"abc123\",\"newPassword\":\"N3w-Passw0rd\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + owner.owner().getEmail() + "\",\"password\":\"N3w-Passw0rd\"}"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(TestDataFactory.PASSWORD).isNotBlank();
    }
}
