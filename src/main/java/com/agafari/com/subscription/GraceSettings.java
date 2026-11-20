package com.agafari.com.subscription;

import java.time.Duration;

/**
 * AGF SUB-R2-01: the grace applied after the period end of a PROVIDER subscription (TA Rev. 3 §4.3; anchored at
 * {@code period_end}). AD-7 (approved): 7 days, recorded on each PROVIDER row ({@link ProviderGrace}). Trials, MANUAL
 * and OWNER rows have no grace.
 */
public record GraceSettings(Duration providerGrace) {

    public GraceSettings {
        if (providerGrace == null || providerGrace.isNegative()) {
            throw new IllegalArgumentException("The provider grace must be zero or positive");
        }
    }

    /** AGF SUB-R2-07: the grace snapshotted on a row. None recorded (a row from before R5-03) means no grace: nothing may extend a period. */
    public static GraceSettings ofSnapshot(Integer days) {
        return ofDays(days == null ? 0 : days);
    }

    public static GraceSettings ofDays(int days) {
        return new GraceSettings(Duration.ofDays(days));
    }
}
