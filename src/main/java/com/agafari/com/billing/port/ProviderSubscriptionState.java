package com.agafari.com.billing.port;

/**
 * AGF SUB-R5-02 (D4, U2): the provider's authoritative state of the subscription a payment created, re-fetched before any
 * cancellation. Nothing here comes from a webhook body.
 *
 * @param agafariCheckoutId  the Agafari checkout the provider's own checkout record names, or null if it can't be established
 * @param agafariBusinessId  the restaurant that checkout record names, or null
 * @param otherOpenInvoices  whether the provider customer has another finalized, open invoice (cancelling would stop its collection)
 */
public record ProviderSubscriptionState(String subscriptionRef, String customerRef, Status status, String agafariCheckoutId, String agafariBusinessId,
                                        boolean otherOpenInvoices, boolean livemode) {

    public enum Status { ACTIVE, PAST_DUE, UNPAID, INCOMPLETE, CANCELED, INCOMPLETE_EXPIRED, NOT_FOUND, OTHER }

    public boolean cancellable() {
        return status == Status.ACTIVE || status == Status.PAST_DUE || status == Status.UNPAID || status == Status.INCOMPLETE;
    }

    public boolean ended() {
        return status == Status.CANCELED || status == Status.INCOMPLETE_EXPIRED;
    }
}
