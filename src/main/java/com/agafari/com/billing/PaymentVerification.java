package com.agafari.com.billing;

/**
 * AGF SUB-R4-08: how the provider re-fetch and the amount, currency and metadata checks ended. {@code UNRESOLVED} is a
 * genuine pending or processing outcome, which is not a failed verification (PO D2).
 */
public enum PaymentVerification {
    VERIFIED, FAILED, UNRESOLVED
}
