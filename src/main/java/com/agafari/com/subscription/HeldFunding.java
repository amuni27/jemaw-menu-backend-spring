package com.agafari.com.subscription;

import com.agafari.com.billing.VerifiedPaymentRef;

/**
 * AGF SUB-R4-15 (PO D3, P1): what the lifecycle and the scheduling service need from billing about a paid downgrade's funding,
 * without depending on billing classes. Implemented by {@code billing.PaymentOutcomeService}, which stays the only builder of
 * {@link VerifiedPaymentRef}.
 */
public interface HeldFunding {

    /** The pointer to a change's funding charge and invoice; re-validated by {@code EvidenceValidator} before use. */
    VerifiedPaymentRef evidence(String paymentId, String invoiceId);

    /**
     * A funded change won't be applied (failed, superseded, cancelled): its held charge gets a customer-funds exception with
     * {@code code}, in the caller's transaction. Nothing happens for an unfunded change. Repeating it changes nothing.
     */
    void release(String changeId, String code, String reason);

    /** Whether paid scheduling is open for this restaurant: its market's billing gate (PO D1). */
    boolean billingActive(String businessId);
}
