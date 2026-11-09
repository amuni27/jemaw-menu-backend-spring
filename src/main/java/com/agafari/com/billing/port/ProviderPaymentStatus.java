package com.agafari.com.billing.port;

/** AGF SUB-R4-04: the provider's own answer on a re-fetch. PENDING is unresolved, not a verification failure (PO D2). */
public enum ProviderPaymentStatus {
    SUCCEEDED, FAILED, PENDING
}
