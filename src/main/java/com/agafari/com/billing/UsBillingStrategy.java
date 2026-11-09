package com.agafari.com.billing;

import com.agafari.com.billing.port.BillingStrategy;

/** AGF SUB-R4-04: the US market's shell; the real adapter arrives with its provider phase. */
public final class UsBillingStrategy extends DelegatingBillingStrategy {

    public UsBillingStrategy(BillingStrategy delegate) {
        super(Market.US, delegate);
    }
}
