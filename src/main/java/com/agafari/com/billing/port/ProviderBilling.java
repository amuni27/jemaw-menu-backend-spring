package com.agafari.com.billing.port;

import com.agafari.com.billing.PriceInterval;

import java.time.Instant;

/**
 * AGF SUB-R5-02 (PO Q1/Q2, U1): what a provider-billed subscription's verified first invoice establishes, from the API
 * re-fetch only. The period is the invoice line's service period and becomes the stored paid period (no recomputation).
 *
 * @param invoiceRef        the verified invoice ({@code billing_invoice.provider_invoice_ref}; for an initial payment also the ledger identity)
 * @param subscriptionRef   the provider subscription ({@code business_subscription.provider_subscription_ref})
 * @param customerRef       the provider customer (stored set-once; never reused for a new checkout in R5-02, PO U5)
 * @param priceRef          the provider price charged ({@code plan_price.provider_price_ref})
 * @param moneyMovementRef  the provider's record of the one qualifying money movement (for Stripe the PaymentIntent), the
 *                          trustworthy lookup a refund (R5-05) starts from
 * @param evidenceProblem   null when the invoice is qualifying evidence (PO U1); otherwise why it isn't. The core then
 *                          treats the payment as unverified (matrix row 0): no charge is recorded and nothing is granted
 */
public record ProviderBilling(String invoiceRef, String subscriptionRef, String customerRef, String priceRef, PriceInterval interval, Instant periodStart, Instant periodEnd,
                              String moneyMovementRef, String evidenceProblem) {

    public ProviderBilling {
        if (invoiceRef == null || invoiceRef.isBlank()) throw new IllegalArgumentException("An invoice reference is required");
        if (evidenceProblem == null) {
            for (String ref : new String[]{subscriptionRef, customerRef, priceRef, moneyMovementRef}) {
                if (ref == null || ref.isBlank()) throw new IllegalArgumentException("Qualifying evidence needs every provider reference");
            }
            if (interval == null) throw new IllegalArgumentException("An interval is required");
            if (periodStart == null || periodEnd == null || !periodEnd.isAfter(periodStart)) throw new IllegalArgumentException("A period needs an end after its start");
        }
    }

    /** Evidence that doesn't qualify: only the invoice and the reason are kept. */
    public static ProviderBilling notQualifying(String invoiceRef, String problem) {
        if (problem == null || problem.isBlank()) throw new IllegalArgumentException("A reason is required");
        return new ProviderBilling(invoiceRef, null, null, null, null, null, null, null, problem);
    }

    public boolean qualifying() {
        return evidenceProblem == null;
    }
}
