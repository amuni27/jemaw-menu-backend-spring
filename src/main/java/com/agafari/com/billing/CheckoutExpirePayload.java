package com.agafari.com.billing;

/** AGF SUB-R4-06: the {@code checkout.expire} job's payload. */
public record CheckoutExpirePayload(String checkoutId) {
}
