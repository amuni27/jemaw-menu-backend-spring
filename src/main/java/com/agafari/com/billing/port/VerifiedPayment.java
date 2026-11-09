package com.agafari.com.billing.port;

import com.agafari.com.billing.Money;

import java.time.Instant;
import java.util.Map;

/**
 * AGF SUB-R4-04: the provider's answer on an API re-fetch (DD §8.2). It is a fact from the provider, not proof for the
 * ledger: only {@code SUCCEEDED} carries a payment time, and SUB-R4-10 decides what any of it means.
 *
 * @param providerPaymentRef identifies one money movement (PO D4); never a checkout, event or refund reference
 * @param paidAt             the provider's authoritative successful-payment time (PD-2a); null unless SUCCEEDED
 */
public record VerifiedPayment(String providerPaymentRef, ProviderPaymentStatus status, Money amount, Instant paidAt, Map<String, String> metadata) {

    public VerifiedPayment {
        if (providerPaymentRef == null || providerPaymentRef.isBlank()) throw new IllegalArgumentException("A provider payment reference is required");
        if (status == null) throw new IllegalArgumentException("A status is required");
        if ((status == ProviderPaymentStatus.SUCCEEDED) != (paidAt != null)) throw new IllegalArgumentException("Only a successful payment has a payment time");
        if (status == ProviderPaymentStatus.SUCCEEDED && amount == null) throw new IllegalArgumentException("A successful payment needs its amount");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
