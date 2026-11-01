package com.agafari.com.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * Stateless email-verification tokens (AGF-S2-22), built like the password-reset tokens (AGF-S2-13A):
 * {@code base64url(payload) "." base64url(HMAC-SHA256(payload))}, the payload holding the user ID, the expiry and a keyed
 * fingerprint of the email the link was sent to. A link is spent once the account is verified (checked and changed in a
 * single conditional update by {@link EmailVerificationService}); changing the email makes earlier links fail.
 * <p>
 * The signing secret is dedicated ({@code APP_EMAIL_VERIFICATION_SECRET}). If it is missing, shorter than 32 characters
 * or equal to the JWT or password-reset secret, verification is off: no token is issued and none verifies.
 */
@Slf4j
@Component
public class EmailVerificationTokens {

    public record Claims(String userId, String emailFingerprint) {
    }

    private static final String VERSION = "ev1";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] key;
    private final Duration ttl;
    private volatile Clock clock = Clock.systemUTC();

    @Autowired
    public EmailVerificationTokens(
            @Value("${app.email-verification.secret:}") String secret,
            @Value("${app.jwt.secret}") String jwtSecret,
            @Value("${app.password-reset.secret:}") String resetSecret,
            @Value("${app.email-verification.token-ttl:24h}") Duration ttl) {
        String trimmed = secret == null ? "" : secret.trim();
        String reason = null;
        if (trimmed.isEmpty()) reason = "APP_EMAIL_VERIFICATION_SECRET is not set";
        else if (trimmed.length() < 32) reason = "APP_EMAIL_VERIFICATION_SECRET is shorter than 32 characters";
        else if (trimmed.equals(jwtSecret == null ? null : jwtSecret.trim())
                || trimmed.equals(resetSecret == null ? null : resetSecret.trim())) {
            reason = "APP_EMAIL_VERIFICATION_SECRET must differ from APP_JWT_SECRET and APP_PASSWORD_RESET_SECRET";
        }
        this.key = reason == null ? trimmed.getBytes(StandardCharsets.UTF_8) : null;
        this.ttl = ttl;
        if (reason != null) {
            log.warn("event=email_verification.disabled reason=\"{}\"", reason); // never the value
        }
    }

    public boolean enabled() {
        return key != null;
    }

    /** A token for this user and email, valid until {@code ttl} from now. */
    public String issue(String userId, String email) {
        if (!enabled()) throw new IllegalStateException("Email verification is not configured");
        long expires = clock.instant().plus(ttl).getEpochSecond();
        String payload = VERSION + "|" + userId + "|" + expires + "|" + fingerprint(email);
        return B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + B64.encodeToString(hmac(payload));
    }

    /** The claims of a correctly signed, unexpired token; empty for anything else (or when verification is off). */
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

    /** True while the user's email is the one the token was issued for. */
    public boolean boundTo(Claims claims, String currentEmail) {
        byte[] expected = fingerprint(currentEmail).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, claims.emailFingerprint().getBytes(StandardCharsets.UTF_8));
    }

    /** Replaces the clock (tests). */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    private String fingerprint(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        return B64.encodeToString(Arrays.copyOf(hmac("email|" + normalized), 16));
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
