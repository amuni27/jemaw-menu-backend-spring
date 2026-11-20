package com.agafari.com.billing.port;

import java.time.Instant;

/**
 * AGF SUB-R5-03 (addendum §4, §8): the provider's current state of one subscription, re-fetched because a state event or the
 * renewal sweep named it. Nothing here comes from a webhook body, and none of it is evidence of a payment.
 *
 * @param endedAt          when the provider ended the subscription; null while it hasn't
 * @param latestInvoiceRef the subscription's latest invoice, re-fetched on its own before anything depends on it
 */
public record ProviderSubscriptionSnapshot(String subscriptionRef, String customerRef, ProviderSubscriptionState.Status status, Instant endedAt, String latestInvoiceRef,
                                           boolean livemode) {

    public ProviderSubscriptionSnapshot {
        if (subscriptionRef == null || subscriptionRef.isBlank()) throw new IllegalArgumentException("A subscription reference is required");
        if (status == null) throw new IllegalArgumentException("A status is required");
    }

    public boolean ended() {
        return status == ProviderSubscriptionState.Status.CANCELED || status == ProviderSubscriptionState.Status.INCOMPLETE_EXPIRED;
    }
}
