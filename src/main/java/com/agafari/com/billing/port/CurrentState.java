package com.agafari.com.billing.port;

/**
 * AGF SUB-R4-04: the committed state a strategy decides on; both null when the restaurant has no current subscription.
 * AGF SUB-R5-02: plus the current row's {@code source} (TRIAL, OWNER, MANUAL, PROVIDER), so a strategy can tell a new
 * purchase, Free with Ads → paid (PO D1) and a trial conversion (gated on AD-12) apart.
 */
public record CurrentState(String subscriptionId, String planCode, String source) {

    /** The R4 shape. */
    public CurrentState(String subscriptionId, String planCode) {
        this(subscriptionId, planCode, null);
    }
}
