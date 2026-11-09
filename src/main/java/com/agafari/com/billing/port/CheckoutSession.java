package com.agafari.com.billing.port;

import com.agafari.com.billing.Money;

import java.time.Instant;

/** AGF SUB-R4-04: the fields of a checkout a strategy needs. Checkout storage arrives with SUB-R4-05. */
public record CheckoutSession(String id, String businessId, CheckoutKind kind, Money expected, Instant expiresAt) {
}
