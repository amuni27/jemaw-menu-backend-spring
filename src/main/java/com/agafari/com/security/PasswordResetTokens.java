package com.agafari.com.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Stateless password-reset tokens (AGF-S2-13A). Nothing is stored: a token is
 * {@code base64url(payload) "." base64url(HMAC-SHA256(payload))} where the payload holds the user ID, the expiry and
 * a keyed fingerprint of the user's current password hash. Changing the password (by reset or on the account page)
 * changes the fingerprint, so every earlier link stops working; links also expire after {@code token-ttl}.
 * <p>
 * The signing secret is dedicated ({@code APP_PASSWORD_RESET_SECRET}). If it is missing, shorter than 32 characters
 * or equal to the JWT secret, password reset is off: no token is issued and none verifies.
 */
@Slf4j
@Component
public class PasswordResetTokens {

    public record Claims(String userId, String fingerprint) {
    }

    private static final String VERSION = "pr1";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] key;
    private final Duration ttl;
    private volatile Clock clock = Clock.systemUTC();

    @Autowired
    public PasswordResetTokens(
            @Value("${app.password-reset.secret:}") String secret,
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.password-reset.token-ttl:30m}") Duration ttl) {
        String reason = null;
        if (secret == null || secret.isBlank()) reason = "APP_PASSWORD_RESET_SECRET is not set";
        else if (secret.trim().length() < 32) reason = "APP_PASSWORD_RESET_SECRET is shorter than 32 characters";
        else if (secret.trim().equals(jwtSecret == null ? null : jwtSecret.trim())) reason = "APP_PASSWORD_RESET_SECRET must differ from APP_JWT_SECRET";

        this.key = reason == null ? secret.trim().getBytes(StandardCharsets.UTF_8) : null;
        this.ttl = ttl;
        if (reason != null) {
            log.warn("event=password_reset.disabled reason=\"{}\"", reason); // never the value
        }
    }

    public boolean enabled() {
        return key != null;
    }

    /** A token for this user, valid until {@code ttl} from now and only while the password hash is unchanged. */
    public String issue(String userId, String passwordHash) {
        if (!enabled()) throw new IllegalStateException("Password reset is not configured");
        long expires = clock.instant().plus(ttl).getEpochSecond();
        String payload = VERSION + "|" + userId + "|" + expires + "|" + fingerprint(passwordHash);
        return B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + B64.encodeToString(hmac(payload));
    }

    /** Same work as {@link #issue} for an email with no account, so both cost the same; the result is discarded. */
    public String issueDecoy() {
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        return issue(UUID.randomUUID().toString(), B64.encodeToString(random));
    }

    /** The claims of a correctly signed, unexpired token; empty for anything else (or when reset is off). */
    public Optional<Claims> verify(String token) {
        if (!enabled() || token == null) return Optional.empty();
        try {
            int dot = token.indexOf('.');
            if (dot <= 0 || dot != token.lastIndexOf('.')) return Optional.empty();
            String payload = new String(B64D.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            byte[] signature = B64D.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(signature, hmac(payload))) return Optional.empty();

            String[] parts = payload.split("\\|", -1);
            if (parts.length != 4 || !VERSION.equals(parts[0])) return Optional.empty();
            if (clock.instant().getEpochSecond() >= Long.parseLong(parts[2])) return Optional.empty();
            return Optional.of(new Claims(parts[1], parts[3]));
        } catch (IllegalArgumentException e) { // bad base64 or number
            return Optional.empty();
        }
    }

    /** True while the user's password hash is the one the token was issued for. */
    public boolean boundTo(Claims claims, String currentPasswordHash) {
        byte[] expected = fingerprint(currentPasswordHash).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, claims.fingerprint().getBytes(StandardCharsets.UTF_8));
    }

    /** Replaces the clock (tests). */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    private String fingerprint(String passwordHash) {
        byte[] full = hmac("fp|" + passwordHash);
        return B64.encodeToString(java.util.Arrays.copyOf(full, 16));
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
