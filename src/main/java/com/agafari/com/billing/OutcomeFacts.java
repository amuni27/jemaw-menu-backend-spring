package com.agafari.com.billing;

import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.ProviderCapabilities;

import java.time.Instant;

/**
 * AGF SUB-R4-08: the committed facts the matrix decides on. Capabilities are read outside any transaction and passed in as
 * immutable facts (PO D1, SUB-R4-04).
 *
 * @param tPay                       the provider's authoritative successful-payment time
 * @param cancelRequestedAt          also set for {@code CREATE_FAILED}; null if never requested
 * @param eligibleAtPayment          {@link EligibilityAtTime} at {@code tPay} (PD-2a)
 * @param fullRefundOfVerifiedCharge an automatic refund is only ever a full refund of a verified charge (R-1)
 */
public record OutcomeFacts(PaymentVerification verification, boolean sessionKnown, boolean completedByAnotherPayment, Instant tPay, Instant expiresAt, Instant cancelRequestedAt,
                           boolean eligibleAtPayment, String replacesSubscriptionId, String currentSubscriptionId, CheckoutKind kind, ProviderCapabilities capabilities,
                           boolean fullRefundOfVerifiedCharge) {
}
