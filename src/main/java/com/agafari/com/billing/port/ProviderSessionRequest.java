package com.agafari.com.billing.port;

import com.agafari.com.billing.Money;
import com.agafari.com.billing.PriceInterval;

import java.time.Instant;
import java.util.Map;

/**
 * AGF SUB-R4-04: what a provider needs to open a hosted checkout. AGF SUB-R4-10: an adapter sends {@code checkoutId} and
 * {@code businessId} as payment metadata ({@code billing.PaymentMetadata}), so a verified payment can be matched to its
 * checkout and restaurant (PO ruling R4).
 *
 * <p>AGF SUB-R5-02 (Q1): a provider-billed subscription also needs the price's billing {@code interval} and its
 * {@code providerPriceRef}. {@code providerCustomerRef} stays null in R5-02 (PO U5: a stored customer is never reused).
 */
public record ProviderSessionRequest(String checkoutId, String businessId, Money amount, Instant expiresAt, PriceInterval interval, String providerPriceRef,
                                     String providerCustomerRef, Map<String, String> metadata) {

    public ProviderSessionRequest {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** The R4 shape. */
    public ProviderSessionRequest(String checkoutId, String businessId, Money amount, Instant expiresAt) {
        this(checkoutId, businessId, amount, expiresAt, null, null, null, null);
    }
}
