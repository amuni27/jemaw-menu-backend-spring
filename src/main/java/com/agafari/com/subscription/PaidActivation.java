package com.agafari.com.subscription;

import com.agafari.com.billing.VerifiedPaymentRef;

import java.time.Instant;

/**
 * AGF SUB-R4-09: a new PROVIDER subscription. AGF SUB-R5-03: {@code graceDays} is null or 7; the row always records
 * {@link ProviderGrace#DAYS} (AD-7), and any other value is refused.
 *
 * @param providerSubscriptionRef US only; null otherwise
 */
public record PaidActivation(String planCode, int configVersion, String priceId, String provider, String providerSubscriptionRef, Integer graceDays, Instant periodStart, Instant periodEnd,
                             VerifiedPaymentRef evidence, Instant now) {
}
