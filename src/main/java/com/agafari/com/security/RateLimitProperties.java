package com.agafari.com.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Authentication rate limits (AGF-S1-19), bound from {@code app.rate-limit.*}.
 * Counters are in memory: each application instance enforces its own limits.
 */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        Rule loginPerEmail,
        Rule loginPerIp,
        Rule registerPerIp,
        Rule changePasswordPerIp,
        Rule forgotPasswordPerEmail, // AGF-S2-13A
        Rule forgotPasswordPerIp,
        Rule resetPasswordPerIp,
        Rule verifyEmailResendPerUser, // AGF-S2-22: successful resends only
        Rule verifyEmailResendPerIp,
        Rule verifyEmailPerIp,
        int maxTrackedKeys
) {
    /** At most {@code limit} events per {@code window}. */
    public record Rule(int limit, Duration window) {
    }
}
