package com.agafari.com.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AGF-S2-13A: stateless reset tokens: signed, 30-minute expiry, bound to the current password hash, never stored. */
class PasswordResetTokensTest {

    private static final String SECRET = "unit-test-password-reset-secret-0123456789";
    private static final String JWT_SECRET = "unit-test-jwt-secret-is-something-else-98765";
    private static final Instant T0 = Instant.parse("2026-10-24T10:00:00Z");

    private static PasswordResetTokens tokens(String secret, Instant now) {
        PasswordResetTokens t = new PasswordResetTokens(secret, JWT_SECRET, Duration.ofMinutes(30));
        t.setClock(Clock.fixed(now, ZoneOffset.UTC));
        return t;
    }

    @Test
    void aFreshTokenVerifiesForItsUserAndCurrentPassword() {
        PasswordResetTokens t = tokens(SECRET, T0);
        String token = t.issue("user-1", "$2a$10$hashA");

        var claims = t.verify(token).orElseThrow();
        assertThat(claims.userId()).isEqualTo("user-1");
        assertThat(t.boundTo(claims, "$2a$10$hashA")).isTrue();
        // the token is opaque: it does not contain the password hash
        assertThat(new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]))).doesNotContain("hashA");
    }

    @Test
    void aChangedPasswordInvalidatesTheToken() {
        PasswordResetTokens t = tokens(SECRET, T0);
        var claims = t.verify(t.issue("user-1", "$2a$10$hashA")).orElseThrow();
        assertThat(t.boundTo(claims, "$2a$10$hashB")).isFalse();
    }

    @Test
    void tokensExpireAfterThirtyMinutes() {
        String token = tokens(SECRET, T0).issue("user-1", "h");
        assertThat(tokens(SECRET, T0.plus(Duration.ofMinutes(29).plusSeconds(59))).verify(token)).isPresent();
        assertThat(tokens(SECRET, T0.plus(Duration.ofMinutes(30))).verify(token)).isEmpty();
    }

    @Test
    void tamperedOrForeignTokensAreRejected() {
        PasswordResetTokens t = tokens(SECRET, T0);
        String token = t.issue("user-1", "h");
        String[] parts = token.split("\\.");

        // payload changed to another user, signature kept
        String otherPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[0])).replace("user-1", "user-2").getBytes());
        assertThat(t.verify(otherPayload + "." + parts[1])).isEmpty();
        // signature changed
        assertThat(t.verify(parts[0] + "." + parts[1].substring(1) + "A")).isEmpty();
        // signed with another secret
        assertThat(t.verify(tokens("another-password-reset-secret-abcdefghijkl", T0).issue("user-1", "h"))).isEmpty();
        // garbage
        for (String bad : new String[]{"", "abc", "a.b.c", "....", "%%%.%%%", token + "x"}) {
            assertThat(t.verify(bad)).as(bad).isEmpty();
        }
    }

    @Test
    void decoyTokensNeverVerifyAgainstARealPassword() {
        PasswordResetTokens t = tokens(SECRET, T0);
        String decoy = t.issueDecoy();
        assertThat(decoy).isNotBlank();
        t.verify(decoy).ifPresent(c -> assertThat(t.boundTo(c, "h")).isFalse());
    }

    @Test
    void resetIsOffWhenTheSecretIsMissingTooShortOrTheJwtSecret() {
        assertThat(tokens(SECRET, T0).enabled()).isTrue();
        for (String bad : new String[]{null, "", "   ", "too-short-secret", JWT_SECRET}) {
            PasswordResetTokens off = tokens(bad, T0);
            assertThat(off.enabled()).as(String.valueOf(bad)).isFalse();
            assertThatThrownBy(() -> off.issue("user-1", "h")).isInstanceOf(IllegalStateException.class);
            assertThat(off.verify("anything.here")).isEmpty();
        }
    }

    @Test
    void theTestOnlyOutboxIsNotPartOfTheApplication() {
        // the demo outbox lives in src/demo and is only compiled with -Pdemo, never into the production artifact
        assertThatThrownBy(() -> Class.forName("com.agafari.com.demo.DemoPasswordResetOutbox"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
