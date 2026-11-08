package com.agafari.com.subscription;

import java.time.Duration;

/**
 * AGF SUB-R2-01: the grace applied after the period end of a PROVIDER subscription (TA Rev. 3 §4.3; anchored at
 * {@code period_end}). The length is an open decision (AD-7); tests use seven days as a fixture only. Trials, MANUAL
 * and OWNER rows have no grace.
 */
public record GraceSettings(Duration providerGrace) {

    public GraceSettings {
        if (providerGrace == null || providerGrace.isNegative()) {
            throw new IllegalArgumentException("The provider grace must be zero or positive");
        }
    }

    public static GraceSettings ofDays(int days) {
        return new GraceSettings(Duration.ofDays(days));
    }
}
