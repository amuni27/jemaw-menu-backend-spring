package com.agafari.com.billing;

/**
 * AGF SUB-R4-10 (PO ruling R4): the metadata an adapter attaches to a provider payment, read back only from the provider's
 * own re-fetch. A payment is matched to a checkout only when every key agrees with Agafari's record; anything missing or
 * conflicting is quarantined, and ownership is never inferred.
 */
public final class PaymentMetadata {

    /** The checkout the payment was made for ({@code ProviderSessionRequest.checkoutId}). */
    public static final String CHECKOUT_ID = "agafari_checkout_id";
    /** The restaurant the checkout belongs to ({@code ProviderSessionRequest.businessId}). */
    public static final String BUSINESS_ID = "agafari_business_id";
    /** The provider's session reference; must equal the recorded one whenever the checkout has one. */
    public static final String PROVIDER_SESSION_REF = "provider_session_ref";

    private PaymentMetadata() {
    }
}
