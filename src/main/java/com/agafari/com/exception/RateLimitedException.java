package com.agafari.com.exception;

/** Too many authentication attempts: 429 with code RATE_LIMITED and a Retry-After header (AGF-S1-19). */
public class RateLimitedException extends RuntimeException {

    public static final String CODE = "RATE_LIMITED";
    public static final String MESSAGE = "Too many attempts. Please wait a few minutes and try again.";

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        super(MESSAGE);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
