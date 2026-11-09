package com.agafari.com.billing;

import static com.agafari.com.billing.PaymentOutcome.Action.ACTIVATE;
import static com.agafari.com.billing.PaymentOutcome.Action.AUTO_REFUND;
import static com.agafari.com.billing.PaymentOutcome.Action.EXCEPTION;
import static com.agafari.com.billing.PaymentOutcome.Action.EXTEND;
import static com.agafari.com.billing.PaymentOutcome.Action.REPLACE;
import static com.agafari.com.billing.PaymentOutcome.Action.UNRESOLVED;

import java.util.Objects;

/**
 * AGF SUB-R4-08 (TA §6.3, DD §8.3): one pure decision, first match wins. No database, clock or provider. Rows 2–5 refund
 * automatically only when that is safe (R-1); row 6 never activates and never refunds until PD-9 (BD-17).
 */
public final class PaymentOutcomeMatrix {

    private PaymentOutcomeMatrix() {
    }

    public static PaymentOutcome decide(OutcomeFacts f) {
        Objects.requireNonNull(f.verification(), "verification");
        if (f.verification() == PaymentVerification.UNRESOLVED) return new PaymentOutcome(UNRESOLVED, -1, null, null); // PO D2: not row 0
        if (f.verification() == PaymentVerification.FAILED) return exception(0, PaymentOutcome.VERIFICATION_FAILED);
        if (!f.sessionKnown()) return exception(1, PaymentOutcome.UNKNOWN_REFERENCE);
        if (f.completedByAnotherPayment()) return refund(f, 2, PaymentOutcome.DUPLICATE_PAYMENT);
        Objects.requireNonNull(f.tPay(), "tPay");
        Objects.requireNonNull(f.expiresAt(), "expiresAt");
        if (f.tPay().isAfter(f.expiresAt())) return refund(f, 3, PaymentOutcome.LATE_PAYMENT);
        if (f.cancelRequestedAt() != null && f.tPay().isAfter(f.cancelRequestedAt())) return refund(f, 4, PaymentOutcome.PAID_AFTER_CANCEL);
        if (!f.eligibleAtPayment()) return refund(f, 5, PaymentOutcome.PLAN_NOT_ELIGIBLE);
        if (!Objects.equals(f.currentSubscriptionId(), f.replacesSubscriptionId())) return exception(6, PaymentOutcome.STATE_CHANGED);
        Objects.requireNonNull(f.kind(), "kind");
        return switch (f.kind()) {
            case NEW -> new PaymentOutcome(ACTIVATE, 7, null, null);
            case RENEWAL -> new PaymentOutcome(EXTEND, 7, null, null);
            case CONVERSION, UPGRADE, DOWNGRADE_RENEWAL -> new PaymentOutcome(REPLACE, 7, null, null);
        };
    }

    private static PaymentOutcome exception(int row, String code) {
        return new PaymentOutcome(EXCEPTION, row, code, code);
    }

    private static PaymentOutcome refund(OutcomeFacts f, int row, String cause) {
        Objects.requireNonNull(f.capabilities(), "capabilities");
        if (f.capabilities().idempotentRefund() && f.fullRefundOfVerifiedCharge()) return new PaymentOutcome(AUTO_REFUND, row, cause, cause);
        return new PaymentOutcome(EXCEPTION, row, PaymentOutcome.REFUND_REQUIRED_MANUAL, cause);
    }
}
