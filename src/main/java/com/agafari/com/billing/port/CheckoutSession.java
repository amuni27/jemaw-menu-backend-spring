package com.agafari.com.billing.port;

import com.agafari.com.billing.Money;
import com.agafari.com.billing.PriceInterval;

import java.time.Instant;

/**
 * AGF SUB-R4-04: the fields of a checkout a strategy needs. Checkout storage arrives with SUB-R4-05. AGF SUB-R5-02 (Q1):
 * plus the quoted price, its interval and provider reference (from {@code plan_price}), and the expected current row.
 */
public record CheckoutSession(String id, String businessId, CheckoutKind kind, Money expected, Instant expiresAt, String priceId, PriceInterval interval,
                              String providerPriceRef, String replacesSubscriptionId) {

    /** The R4 shape. */
    public CheckoutSession(String id, String businessId, CheckoutKind kind, Money expected, Instant expiresAt) {
        this(id, businessId, kind, expected, expiresAt, null, null, null, null);
    }
}
