package com.agafari.com.billing;

/** AGF SUB-R4-09: test-only access to the package-private constructor. */
public final class TestEvidence {

    private TestEvidence() {
    }

    public static VerifiedPaymentRef ref(String paymentId, String invoiceId) {
        return new VerifiedPaymentRef(paymentId, invoiceId);
    }
}
