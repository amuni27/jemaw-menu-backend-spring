package com.agafari.com.billing.port;

import com.agafari.com.billing.Money;

import java.time.Instant;

/**
 * AGF SUB-R4-04: what a provider needs to open a hosted checkout. AGF SUB-R4-10: an adapter sends {@code checkoutId} and
 * {@code businessId} as payment metadata ({@code billing.PaymentMetadata}), so a verified payment can be matched to its
 * checkout and restaurant (PO ruling R4).
 */
public record ProviderSessionRequest(String checkoutId, String businessId, Money amount, Instant expiresAt) {
}
