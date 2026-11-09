package com.agafari.com.billing.port;

import java.time.Duration;

/** AGF SUB-R4-04 (BD-25): what a provider has been confirmed to do. Anything unconfirmed is false. */
public record ProviderCapabilities(boolean idempotentCreate, boolean sessionExpire, boolean idempotentRefund, Duration idempotencyWindow, boolean refundLookup) {

    public ProviderCapabilities {
        if (idempotencyWindow == null || idempotencyWindow.isNegative()) throw new IllegalArgumentException("An idempotency window of zero or more is required");
        if ((idempotentCreate || idempotentRefund) && idempotencyWindow.isZero()) throw new IllegalArgumentException("Idempotent calls need a confirmed window");
    }

    public static ProviderCapabilities none() {
        return new ProviderCapabilities(false, false, false, Duration.ZERO, false);
    }
}
