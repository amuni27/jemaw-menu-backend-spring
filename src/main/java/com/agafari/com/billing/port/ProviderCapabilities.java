package com.agafari.com.billing.port;

import java.time.Duration;

/**
 * AGF SUB-R4-04 (BD-25): what a provider has been confirmed to do. Anything unconfirmed is false. AGF SUB-R5-02 (U2):
 * {@code cancelSubscription}: the provider can cancel a subscription it created, immediately, without a final invoice,
 * proration, refund or credit.
 */
public record ProviderCapabilities(boolean idempotentCreate, boolean sessionExpire, boolean idempotentRefund, Duration idempotencyWindow, boolean refundLookup,
                                   boolean cancelSubscription) {

    public ProviderCapabilities {
        if (idempotencyWindow == null || idempotencyWindow.isNegative()) throw new IllegalArgumentException("An idempotency window of zero or more is required");
        if ((idempotentCreate || idempotentRefund) && idempotencyWindow.isZero()) throw new IllegalArgumentException("Idempotent calls need a confirmed window");
    }

    /** The R4 shape: no subscription cancellation. */
    public ProviderCapabilities(boolean idempotentCreate, boolean sessionExpire, boolean idempotentRefund, Duration idempotencyWindow, boolean refundLookup) {
        this(idempotentCreate, sessionExpire, idempotentRefund, idempotencyWindow, refundLookup, false);
    }

    public static ProviderCapabilities none() {
        return new ProviderCapabilities(false, false, false, Duration.ZERO, false, false);
    }
}
