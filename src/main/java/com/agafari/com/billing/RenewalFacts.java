package com.agafari.com.billing;

import com.agafari.com.billing.port.ProviderCapabilities;

/**
 * AGF SUB-R5-03 (addendum §10): the committed facts the renewal rows decide on, all established under the restaurant lock
 * from the re-fetched invoice and Agafari's rows. Capabilities were read outside any transaction.
 *
 * @param subscriptionKnown          the verified provider subscription is exactly one Agafari PROVIDER row of this provider
 * @param alreadyApplied             this invoice is already in the ledger
 * @param customerMatches            the invoice's customer is the restaurant's stored customer (U5)
 * @param evidenceQualifying         PO U1 holds for the invoice
 * @param priceAndAmountMatch        the price charged is the row's price, and amount, currency and market match it
 * @param periodTakenByAnotherInvoice another invoice already paid the period this one starts
 * @param periodContiguous           the line period starts exactly at the row's {@code period_end} (PD-3)
 * @param periodLaterThanExpected    the line period starts after the row's {@code period_end} (an earlier invoice is missing)
 * @param beforeGraceEnd             now is before {@code period_end + grace}
 * @param intervalMatches            the line period is one billing interval of the row's price (one day's tolerance)
 * @param rowEnded                   the row is EXPIRED
 * @param paidAfterRowEnded          the payment time is after the row's {@code ended_at}
 * @param fullRefundOfVerifiedCharge an automatic refund is only ever a full refund of a verified charge (R-1)
 */
public record RenewalFacts(boolean subscriptionKnown, boolean alreadyApplied, boolean customerMatches, boolean evidenceQualifying, boolean priceAndAmountMatch,
                           boolean periodTakenByAnotherInvoice, boolean periodContiguous, boolean periodLaterThanExpected, boolean beforeGraceEnd, boolean intervalMatches,
                           boolean rowEnded, boolean paidAfterRowEnded, ProviderCapabilities capabilities, boolean fullRefundOfVerifiedCharge) {

    public RenewalFacts withSubscriptionKnown(boolean v) {
        return new RenewalFacts(v, alreadyApplied, customerMatches, evidenceQualifying, priceAndAmountMatch, periodTakenByAnotherInvoice, periodContiguous, periodLaterThanExpected,
                beforeGraceEnd, intervalMatches, rowEnded, paidAfterRowEnded, capabilities, fullRefundOfVerifiedCharge);
    }

    public RenewalFacts withAlreadyApplied(boolean v) {
        return new RenewalFacts(subscriptionKnown, v, customerMatches, evidenceQualifying, priceAndAmountMatch, periodTakenByAnotherInvoice, periodContiguous, periodLaterThanExpected,
                beforeGraceEnd, intervalMatches, rowEnded, paidAfterRowEnded, capabilities, fullRefundOfVerifiedCharge);
    }

    public RenewalFacts withCustomerMatches(boolean v) {
        return new RenewalFacts(subscriptionKnown, alreadyApplied, v, evidenceQualifying, priceAndAmountMatch, periodTakenByAnotherInvoice, periodContiguous, periodLaterThanExpected,
                beforeGraceEnd, intervalMatches, rowEnded, paidAfterRowEnded, capabilities, fullRefundOfVerifiedCharge);
    }

    public RenewalFacts withEvidenceQualifying(boolean v) {
        return new RenewalFacts(subscriptionKnown, alreadyApplied, customerMatches, v, priceAndAmountMatch, periodTakenByAnotherInvoice, periodContiguous, periodLaterThanExpected,
                beforeGraceEnd, intervalMatches, rowEnded, paidAfterRowEnded, capabilities, fullRefundOfVerifiedCharge);
    }

    public RenewalFacts withPriceAndAmountMatch(boolean v) {
        return new RenewalFacts(subscriptionKnown, alreadyApplied, customerMatches, evidenceQualifying, v, periodTakenByAnotherInvoice, periodContiguous, periodLaterThanExpected,
                beforeGraceEnd, intervalMatches, rowEnded, paidAfterRowEnded, capabilities, fullRefundOfVerifiedCharge);
    }

    public RenewalFacts withPeriodTakenByAnotherInvoice(boolean v) {
        return new RenewalFacts(subscriptionKnown, alreadyApplied, customerMatches, evidenceQualifying, priceAndAmountMatch, v, periodContiguous, periodLaterThanExpected,
                beforeGraceEnd, intervalMatches, rowEnded, paidAfterRowEnded, capabilities, fullRefundOfVerifiedCharge);
    }

    public RenewalFacts withPeriodContiguous(boolean v) {
        return new RenewalFacts(subscriptionKnown, alreadyApplied, customerMatches, evidenceQualifying, priceAndAmountMatch, periodTakenByAnotherInvoice, v, periodLaterThanExpected,
                beforeGraceEnd, intervalMatches, rowEnded, paidAfterRowEnded, capabilities, fullRefundOfVerifiedCharge);
    }

    public RenewalFacts withPeriodLaterThanExpected(boolean v) {
        return new RenewalFacts(subscriptionKnown, alreadyApplied, customerMatches, evidenceQualifying, priceAndAmountMatch, periodTakenByAnotherInvoice, false, v,
                beforeGraceEnd, intervalMatches, rowEnded, paidAfterRowEnded, capabilities, fullRefundOfVerifiedCharge);
    }

    public RenewalFacts withBeforeGraceEnd(boolean v) {
        return new RenewalFacts(subscriptionKnown, alreadyApplied, customerMatches, evidenceQualifying, priceAndAmountMatch, periodTakenByAnotherInvoice, periodContiguous,
                periodLaterThanExpected, v, intervalMatches, rowEnded, paidAfterRowEnded, capabilities, fullRefundOfVerifiedCharge);
    }

    public RenewalFacts withIntervalMatches(boolean v) {
        return new RenewalFacts(subscriptionKnown, alreadyApplied, customerMatches, evidenceQualifying, priceAndAmountMatch, periodTakenByAnotherInvoice, periodContiguous,
                periodLaterThanExpected, beforeGraceEnd, v, rowEnded, paidAfterRowEnded, capabilities, fullRefundOfVerifiedCharge);
    }

    public RenewalFacts withRowEnded(boolean v) {
        return new RenewalFacts(subscriptionKnown, alreadyApplied, customerMatches, evidenceQualifying, priceAndAmountMatch, periodTakenByAnotherInvoice, periodContiguous,
                periodLaterThanExpected, beforeGraceEnd, intervalMatches, v, paidAfterRowEnded, capabilities, fullRefundOfVerifiedCharge);
    }

    public RenewalFacts withPaidAfterRowEnded(boolean v) {
        return new RenewalFacts(subscriptionKnown, alreadyApplied, customerMatches, evidenceQualifying, priceAndAmountMatch, periodTakenByAnotherInvoice, periodContiguous,
                periodLaterThanExpected, beforeGraceEnd, intervalMatches, rowEnded, v, capabilities, fullRefundOfVerifiedCharge);
    }

    public RenewalFacts withCapabilities(ProviderCapabilities v) {
        return new RenewalFacts(subscriptionKnown, alreadyApplied, customerMatches, evidenceQualifying, priceAndAmountMatch, periodTakenByAnotherInvoice, periodContiguous,
                periodLaterThanExpected, beforeGraceEnd, intervalMatches, rowEnded, paidAfterRowEnded, v, fullRefundOfVerifiedCharge);
    }

    public RenewalFacts withFullRefund(boolean v) {
        return new RenewalFacts(subscriptionKnown, alreadyApplied, customerMatches, evidenceQualifying, priceAndAmountMatch, periodTakenByAnotherInvoice, periodContiguous,
                periodLaterThanExpected, beforeGraceEnd, intervalMatches, rowEnded, paidAfterRowEnded, capabilities, v);
    }
}
