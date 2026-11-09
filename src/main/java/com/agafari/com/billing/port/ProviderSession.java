package com.agafari.com.billing.port;

/** AGF SUB-R4-04: a hosted checkout at the provider. The session reference is never a payment reference (D4). */
public record ProviderSession(String providerSessionRef, String url) {
}
