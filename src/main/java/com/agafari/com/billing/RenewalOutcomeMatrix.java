package com.agafari.com.billing;

import java.util.Objects;

import static com.agafari.com.billing.RenewalOutcome.Action.ALREADY_DONE;
import static com.agafari.com.billing.RenewalOutcome.Action.AUTO_REFUND;
import static com.agafari.com.billing.RenewalOutcome.Action.EXCEPTION;
import static com.agafari.com.billing.RenewalOutcome.Action.EXTEND;
import static com.agafari.com.billing.RenewalOutcome.Action.QUARANTINE;
import static com.agafari.com.billing.RenewalOutcome.Action.RETRY;

/**
 * AGF SUB-R5-03 (addendum §10, P-R4): the renewal rows, a pure function beside {@link PaymentOutcomeMatrix}; first match wins.
 * Row R0 (an unresolved or failed re-fetch) is decided before this, exactly as for checkout payments.
 *
 * <p>Order: R1, R2, R3, R4, R5, <b>R7 before R6</b>, R8, R9. A second invoice for an already-paid period starts at the row's
 * previous period end, so the contiguity check (R6) would otherwise always claim it first and R7 could never be reached.
 */
public final class RenewalOutcomeMatrix {

    private RenewalOutcomeMatrix() {
    }

    public static RenewalOutcome decide(RenewalFacts f) {
        Objects.requireNonNull(f.capabilities(), "capabilities");
        if (!f.subscriptionKnown()) return new RenewalOutcome(QUARANTINE, "R1", RenewalOutcome.UNKNOWN_SUBSCRIPTION, RenewalOutcome.UNKNOWN_SUBSCRIPTION, RenewalOutcome.Refund.NONE);
        if (f.alreadyApplied()) return new RenewalOutcome(ALREADY_DONE, "R2", null, null, RenewalOutcome.Refund.NONE);
        if (!f.customerMatches()) return held("R3", RenewalOutcome.CUSTOMER_MISMATCH);
        if (!f.evidenceQualifying()) return held("R4", RenewalOutcome.EVIDENCE_NOT_QUALIFYING);
        if (!f.priceAndAmountMatch()) return held("R5", RenewalOutcome.AMOUNT_MISMATCH);
        if (f.periodTakenByAnotherInvoice()) return refund(f, "R7", RenewalOutcome.DUPLICATE_PERIOD);
        if (!f.intervalMatches()) return held("R6", RenewalOutcome.PERIOD_MISMATCH);
        if (!f.periodContiguous()) {
            if (f.periodLaterThanExpected() && f.beforeGraceEnd() && !f.rowEnded()) {
                return new RenewalOutcome(RETRY, "R6", RenewalOutcome.PENDING_ORDER, RenewalOutcome.PENDING_ORDER, RenewalOutcome.Refund.NONE);
            }
            return held("R6", RenewalOutcome.PERIOD_MISMATCH);
        }
        if (f.rowEnded()) return f.paidAfterRowEnded() ? refund(f, "R8", RenewalOutcome.PAID_AFTER_EXPIRY) : held("R8", RenewalOutcome.ROW_ENDED);
        return new RenewalOutcome(EXTEND, "R9", null, null, RenewalOutcome.Refund.NONE);
    }

    /** P-R4: held for manual review; never refunded automatically. */
    private static RenewalOutcome held(String row, String code) {
        return new RenewalOutcome(EXCEPTION, row, code, code, RenewalOutcome.Refund.MANUAL_HOLD);
    }

    /** R-1: automatic only with a confirmed idempotent refund of the whole verified charge; otherwise the manual fallback. */
    private static RenewalOutcome refund(RenewalFacts f, String row, String cause) {
        if (f.capabilities().idempotentRefund() && f.fullRefundOfVerifiedCharge()) return new RenewalOutcome(AUTO_REFUND, row, cause, cause, RenewalOutcome.Refund.AUTOMATIC);
        return new RenewalOutcome(EXCEPTION, row, PaymentOutcome.REFUND_REQUIRED_MANUAL, cause, RenewalOutcome.Refund.MANUAL_FALLBACK);
    }
}
