package com.agafari.com.billing.port;

import java.time.Instant;

/**
 * AGF SUB-R5-03 (addendum §4, §5): the provider's current state of one invoice, re-fetched because a state event named it. It
 * only decides whether a renewal failed; it is never payment evidence (that is {@link VerifiedPayment}).
 *
 * @param billingReason the provider's billing reason ({@code subscription_cycle} for a renewal)
 * @param periodStart   the single line's service period start (the period the invoice would pay); null if it has no single line
 * @param attempted     whether the provider has tried to collect it
 */
public record ProviderInvoiceState(String invoiceRef, Status status, String billingReason, String subscriptionRef, String customerRef, Instant periodStart, Instant periodEnd,
                                   boolean attempted, boolean livemode) {

    public enum Status { DRAFT, OPEN, PAID, VOID, UNCOLLECTIBLE, OTHER }

    public static final String RENEWAL_REASON = "subscription_cycle";

    public ProviderInvoiceState {
        if (invoiceRef == null || invoiceRef.isBlank()) throw new IllegalArgumentException("An invoice reference is required");
        if (status == null) throw new IllegalArgumentException("A status is required");
    }

    /** A provider-initiated renewal of a subscription (addendum §1). */
    public boolean renewal() {
        return RENEWAL_REASON.equals(billingReason) && subscriptionRef != null && !subscriptionRef.isBlank();
    }
}
