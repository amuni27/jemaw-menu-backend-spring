package com.agafari.com.subscription;

import com.agafari.com.billing.VerifiedPaymentRef;

import java.time.Instant;

/** AGF SUB-R4-09: one more paid period on the same subscription. */
public record PeriodExtension(Instant periodStart, Instant periodEnd, VerifiedPaymentRef evidence, Instant now) {
}
