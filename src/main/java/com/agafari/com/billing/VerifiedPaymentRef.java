package com.agafari.com.billing;

/**
 * AGF SUB-R4-09 (BD-16): a pointer to a payment and invoice, not proof: {@code EvidenceValidator} re-loads and checks both
 * under the lock. Only {@code billing.PaymentOutcomeService} (SUB-R4-10) may construct one; until it exists no production
 * code can, so no paid lifecycle command is reachable.
 */
public final class VerifiedPaymentRef {

    private final String paymentId;
    private final String invoiceId;

    VerifiedPaymentRef(String paymentId, String invoiceId) {
        this.paymentId = paymentId;
        this.invoiceId = invoiceId;
    }

    public String paymentId() {
        return paymentId;
    }

    public String invoiceId() {
        return invoiceId;
    }
}
