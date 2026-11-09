package com.agafari.com.billing;

import com.agafari.com.billing.port.BillingStrategy;

/** AGF SUB-R4-04: the ET market's shell; the real adapter arrives with its provider phase. */
public final class EtBillingStrategy extends DelegatingBillingStrategy {

    public EtBillingStrategy(BillingStrategy delegate) {
        super(Market.ET, delegate);
    }
}
