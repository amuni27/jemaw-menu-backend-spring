package com.agafari.com.security;

import com.agafari.com.exception.RateLimitedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, fixed-window rate limits for authentication endpoints (AGF-S1-19):
 * failed logins per email and per IP, registrations per IP, change-password attempts per IP.
 * Single-instance only: with several instances each one counts separately.
 */
@Slf4j
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class AuthRateLimiter {

    private record Window(Instant start, int count) {
    }

    private final RateLimitProperties props;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private volatile Clock clock = Clock.systemUTC();

    public AuthRateLimiter(RateLimitProperties props) {
        this.props = props;
    }

    // ------------------------------------------------------------------ login

    /** Refuses the attempt while the email or the IP has too many recent failures. */
    public void checkLogin(String email, String ip) {
        if (!props.enabled()) return;
        refuseIfBlocked("login_email", "login-email:" + normalize(email), props.loginPerEmail(), LogMasking.maskEmail(email));
        refuseIfBlocked("login_ip", "login-ip:" + ip, props.loginPerIp(), maskIp(ip));
    }

    public void loginFailed(String email, String ip) {
        if (!props.enabled()) return;
        count("login-email:" + normalize(email), props.loginPerEmail());
        count("login-ip:" + ip, props.loginPerIp());
    }

    /** A successful login clears the email's failure count (the IP count keeps running). */
    public void loginSucceeded(String email) {
        windows.remove("login-email:" + normalize(email));
    }

    // ------------------------------------------------------------------ attempt-based rules

    public void countRegistration(String ip) {
        checkAndCount("register_ip", "register-ip:" + ip, props.registerPerIp(), maskIp(ip));
    }

    public void countChangePassword(String ip) {
        checkAndCount("change_password_ip", "change-password-ip:" + ip, props.changePasswordPerIp(), maskIp(ip));
    }

    /** AGF-S2-13A: reset-link requests per email (registered or not, so limits reveal nothing) and per IP. */
    public void countForgotPassword(String email, String ip) {
        checkAndCount("forgot_password_email", "forgot-email:" + normalize(email), props.forgotPasswordPerEmail(), LogMasking.maskEmail(email));
        checkAndCount("forgot_password_ip", "forgot-ip:" + ip, props.forgotPasswordPerIp(), maskIp(ip));
    }

    /** AGF-S2-13A: reset submissions per IP (limits guessing at links). */
    public void countResetPassword(String ip) {
        checkAndCount("reset_password_ip", "reset-ip:" + ip, props.resetPasswordPerIp(), maskIp(ip));
    }

    /**
     * AGF-S2-22: before a resend. Refuses an owner who already had {@code verifyEmailResendPerUser} links sent (only
     * successful sends are counted, see {@link #verificationResendSent}); every attempt counts per IP.
     */
    public void checkVerificationResend(String userId, String ip) {
        if (!props.enabled()) return;
        refuseIfBlocked("verify_resend_user", "verify-resend-user:" + userId, props.verifyEmailResendPerUser(), userId);
        checkAndCount("verify_resend_ip", "verify-resend-ip:" + ip, props.verifyEmailResendPerIp(), maskIp(ip));
    }

    /** AGF-S2-22: a resend that the email service accepted. */
    public void verificationResendSent(String userId) {
        if (!props.enabled()) return;
        count("verify-resend-user:" + userId, props.verifyEmailResendPerUser());
    }

    /** AGF-S2-22: verification-link submissions per IP (limits guessing at links). */
    public void countVerifyEmail(String ip) {
        checkAndCount("verify_email_ip", "verify-email-ip:" + ip, props.verifyEmailPerIp(), maskIp(ip));
    }

    // ------------------------------------------------------------------ test support

    /** Clears all counters (tests). */
    public void reset() {
        windows.clear();
    }

    /** Replaces the clock (tests use a controllable clock). */
    public void setClock(Clock clock) {
        this.clock = clock;
    }

    // ------------------------------------------------------------------ internals

    private void checkAndCount(String rule, String key, RateLimitProperties.Rule limit, String maskedKey) {
        if (!props.enabled()) return;
        refuseIfBlocked(rule, key, limit, maskedKey);
        count(key, limit);
    }

    private void refuseIfBlocked(String rule, String key, RateLimitProperties.Rule limit, String maskedKey) {
        Instant now = clock.instant();
        Window w = windows.get(key);
        if (w == null || expired(w, limit, now) || w.count() < limit.limit()) return;

        long retryAfter = Duration.between(now, w.start().plus(limit.window())).toSeconds() + 1;
        log.warn("event=auth.rate_limited rule={} key={} retry_after_s={}", rule, maskedKey, retryAfter);
        throw new RateLimitedException(retryAfter);
    }

    private void count(String key, RateLimitProperties.Rule limit) {
        Instant now = clock.instant();
        windows.compute(key, (k, w) -> (w == null || expired(w, limit, now)) ? new Window(now, 1) : new Window(w.start(), w.count() + 1));
        if (windows.size() > props.maxTrackedKeys()) evict(now);
    }

    private static boolean expired(Window w, RateLimitProperties.Rule limit, Instant now) {
        return !now.isBefore(w.start().plus(limit.window()));
    }

    /** Bounded memory: drop windows older than the longest rule, then arbitrary entries if still over the cap. */
    private void evict(Instant now) {
        Duration longest = longestWindow();
        windows.entrySet().removeIf(e -> !now.isBefore(e.getValue().start().plus(longest)));
        Iterator<String> it = windows.keySet().iterator();
        while (windows.size() > props.maxTrackedKeys() && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private Duration longestWindow() {
        Duration max = props.loginPerEmail().window();
        for (RateLimitProperties.Rule r : new RateLimitProperties.Rule[]{props.loginPerIp(), props.registerPerIp(), props.changePasswordPerIp(),
                props.forgotPasswordPerEmail(), props.forgotPasswordPerIp(), props.resetPasswordPerIp(),
                props.verifyEmailResendPerUser(), props.verifyEmailResendPerIp(), props.verifyEmailPerIp()}) {
            if (r.window().compareTo(max) > 0) max = r.window();
        }
        return max;
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** {@code 203.0.113.55} → {@code 203.0.113.x}; IPv6 keeps the first four groups. */
    static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) return "-";
        if (ip.contains(".") && !ip.contains(":")) {
            int last = ip.lastIndexOf('.');
            return ip.substring(0, last) + ".x";
        }
        String[] groups = ip.split(":");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(4, groups.length); i++) b.append(groups[i]).append(':');
        return b.append('x').toString();
    }
}
