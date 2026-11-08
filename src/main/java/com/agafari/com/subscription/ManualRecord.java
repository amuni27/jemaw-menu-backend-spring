package com.agafari.com.subscription;

import java.time.Instant;

/** AGF SUB-R2-02: what a Super Admin manual subscription record consists of ({@code periodEnd} may be null: open-ended). */
public record ManualRecord(String planCode, int configVersion, Instant periodEnd, Instant now) {
}
