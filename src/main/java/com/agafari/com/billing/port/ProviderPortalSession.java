package com.agafari.com.billing.port;

/**
 * AGF SUB-R5-03-E (D-R5, N5): a provider-hosted page where the owner updates their card (Stripe Customer Portal). The URL
 * is a short-lived bearer link to the owner's own billing details: it is returned to the owner and never logged or audited.
 */
public record ProviderPortalSession(String url) {

    public ProviderPortalSession {
        if (url == null || !url.startsWith("https://")) throw new IllegalArgumentException("A portal session needs an https URL");
    }

    @Override
    public String toString() {
        return "ProviderPortalSession[url=<redacted>]";
    }
}
