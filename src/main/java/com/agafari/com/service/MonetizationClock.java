package com.agafari.com.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * AGF E4-M5: the time used for trials (start, end, effective expiry, the expiry job). System UTC time; tests can move it
 * forward with {@link #setOffset(Duration)} so 30-day trials are testable without waiting.
 */
@Component
public class MonetizationClock {

    private volatile Duration offset = Duration.ZERO;

    public Instant now() {
        return Instant.now().plus(offset);
    }

    /** Tests only. */
    public void setOffset(Duration offset) {
        this.offset = offset;
    }

    /** Tests only. */
    public void reset() {
        this.offset = Duration.ZERO;
    }
}
