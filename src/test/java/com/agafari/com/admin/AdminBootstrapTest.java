package com.agafari.com.admin;

import com.agafari.com.enums.UserRole;
import com.agafari.com.enums.UserStatus;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.security.AdminBootstrap;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF E4-M1 (AC1–AC4, SC1–SC3): the first Super Admin is created at startup only in an explicitly authorized local/test
 * environment, never with the prod profile, from two environment variables, once. It never resets a password, never
 * promotes an existing account and never creates a second admin. Credentials here are random per run (SC2).
 */
@ExtendWith(OutputCaptureExtension.class)
class AdminBootstrapTest extends AbstractIntegrationTest {

    @Autowired private AdminBootstrap bootstrap;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    /** A random password that meets the S1-17 policy; never a literal in source (SC2). */
    private static String randomStrongPassword() {
        return "Aa1!" + UUID.randomUUID();
    }

    private static String randomEmail() {
        return "root-" + UUID.randomUUID().toString().substring(0, 8) + "@admin.agafari.local";
    }

    private List<User> admins() {
        return users.findAll().stream().filter(u -> u.getRole() == UserRole.ADMIN).toList();
    }

    @Test
    void ac1CreatesExactlyOneActiveAdminWithoutABusinessAndAuditsIt(CapturedOutput output) {
        String email = randomEmail();
        String password = randomStrongPassword();

        assertThat(bootstrap.bootstrap(email, password, true, false)).isEqualTo(AdminBootstrap.Result.CREATED);

        List<User> admins = admins();
        assertThat(admins).hasSize(1);
        User admin = admins.get(0);
        assertThat(admin.getEmail()).isEqualTo(email);
        assertThat(admin.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(admin.getBusiness()).isNull();
        assertThat(passwordEncoder.matches(password, admin.getPasswordHash())).isTrue();
        assertThat(jdbc.queryForList("select action || '/' || outcome || '/' || actor_label from admin_audit_event", String.class))
                .containsExactly("ADMIN_BOOTSTRAP_CREATED/SUCCESS/system");
        assertThat(output.getAll()).doesNotContain(password).doesNotContain(email); // SC2: email masked, password never
    }

    @Test
    void ac2AndSc3ASecondRunNeverResetsThePasswordOrAddsAnAdmin() {
        String email = randomEmail();
        String original = randomStrongPassword();
        bootstrap.bootstrap(email, original, true, false);
        String hash = admins().get(0).getPasswordHash();

        assertThat(bootstrap.bootstrap(email, randomStrongPassword(), true, false)).isEqualTo(AdminBootstrap.Result.ALREADY_BOOTSTRAPPED);
        assertThat(bootstrap.bootstrap(randomEmail(), randomStrongPassword(), true, false)).isEqualTo(AdminBootstrap.Result.ALREADY_BOOTSTRAPPED);

        assertThat(admins()).hasSize(1);
        assertThat(admins().get(0).getPasswordHash()).isEqualTo(hash);
        assertThat(passwordEncoder.matches(original, admins().get(0).getPasswordHash())).isTrue();
    }

    @Test
    void ac3RefusedWithTheProdProfileEvenWhenAuthorized() {
        assertThatThrownBy(() -> bootstrap.bootstrap(randomEmail(), randomStrongPassword(), true, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not allowed in production");
        assertThat(admins()).isEmpty();
    }

    @Test
    void sc1RefusedWithoutExplicitAuthorizationEvenWhenNotProd() {
        assertThatThrownBy(() -> bootstrap.bootstrap(randomEmail(), randomStrongPassword(), false, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not authorized in this environment");
        assertThat(admins()).isEmpty();
    }

    @Test
    void ac3AndSc3AnExistingOwnerIsNeverPromoted() {
        Tenant owner = data.tenant("bootstrap-owner");
        assertThatThrownBy(() -> bootstrap.bootstrap(owner.owner().getEmail(), randomStrongPassword(), true, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("already belongs to an account");
        assertThat(users.findById(owner.owner().getId()).orElseThrow().getRole()).isEqualTo(UserRole.VENUE);
        assertThat(admins()).isEmpty();
    }

    @Test
    void ac3AWeakPasswordIsRefused() {
        assertThatThrownBy(() -> bootstrap.bootstrap(randomEmail(), "short", true, false))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("password policy");
        assertThat(admins()).isEmpty();
    }

    @Test
    void withoutBothVariablesNothingHappens() {
        assertThat(bootstrap.bootstrap("", randomStrongPassword(), true, false)).isEqualTo(AdminBootstrap.Result.NOT_REQUESTED);
        assertThat(bootstrap.bootstrap(randomEmail(), "", true, false)).isEqualTo(AdminBootstrap.Result.NOT_REQUESTED);
        assertThat(admins()).isEmpty();
    }

    @Test
    void sc1AndSc2CommittedConfigurationNeverAuthorizesBootstrapOrHoldsCredentials() throws Exception {
        for (String file : List.of("application.yml", "application-prod.yml")) {
            String yml = new ClassPathResource(file).getContentAsString(StandardCharsets.UTF_8);
            assertThat(yml).as(file).doesNotContain("environment-authorized: true");
        }
        String main = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);
        assertThat(main).contains("email: ${APP_ADMIN_BOOTSTRAP_EMAIL:}").contains("password: ${APP_ADMIN_BOOTSTRAP_PASSWORD:}");
    }

    @Test
    void ac4OwnerRegistrationAlwaysCreatesAVenueOwner() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/register")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("""
                        {"fullName":"Owner","email":"try-admin@owner.test","password":"Str0ng!Pass","businessName":"Cafe",
                         "businessPhone":"+251911444555","customSubdomain":"try-admin","open24_7":true,"country":"ET",
                         "locationUrl":"https://maps.app.goo.gl/AbC123xyz","role":"ADMIN"}"""))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        Map<String, Object> row = jdbc.queryForMap("select role from users where email = 'try-admin@owner.test'");
        assertThat(row.get("role")).isEqualTo("VENUE");
    }
}
