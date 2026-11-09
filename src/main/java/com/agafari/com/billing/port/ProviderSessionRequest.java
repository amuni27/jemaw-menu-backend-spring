package com.agafari.com.billing.port;

import com.agafari.com.billing.Money;

import java.time.Instant;

/** AGF SUB-R4-04: what a provider needs to open a hosted checkout. */
public record ProviderSessionRequest(String checkoutId, Money amount, Instant expiresAt) {
}
