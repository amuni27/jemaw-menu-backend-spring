package com.agafari.com.subscription;

import com.agafari.com.billing.VerifiedPaymentRef;

import java.time.Instant;

/**
 * AGF SUB-R4-09: a new PROVIDER subscription. {@code graceDays} stays null while AD-7 is open, which means no grace.
 *
 * @param providerSubscriptionRef US only; null otherwise
 */
public record PaidActivation(String planCode, int configVersion, String priceId, String provider, String providerSubscriptionRef, Integer graceDays, Instant periodStart, Instant periodEnd,
                             VerifiedPaymentRef evidence, Instant now) {
}
