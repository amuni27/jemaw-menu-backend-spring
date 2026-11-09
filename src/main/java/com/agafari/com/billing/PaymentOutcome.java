package com.agafari.com.billing;

/**
 * AGF SUB-R4-08: what the matrix decided, and nothing else: applying it is SUB-R4-10.
 *
 * @param row   the matrix row (TA §6.3); -1 when no row applies because the outcome is unresolved
 * @param code  the exception code, or null when no exception or refund is involved
 * @param cause the row's own code when {@code code} is the manual-refund fallback; otherwise equal to {@code code}
 */
public record PaymentOutcome(Action action, int row, String code, String cause) {

    public enum Action { ACTIVATE, EXTEND, REPLACE, AUTO_REFUND, EXCEPTION, UNRESOLVED }

    public static final String VERIFICATION_FAILED = "VERIFICATION_FAILED";
    public static final String UNKNOWN_REFERENCE = "UNKNOWN_REFERENCE";
    public static final String DUPLICATE_PAYMENT = "DUPLICATE_PAYMENT";
    public static final String LATE_PAYMENT = "LATE_PAYMENT";
    public static final String PAID_AFTER_CANCEL = "PAID_AFTER_CANCEL";
    public static final String PLAN_NOT_ELIGIBLE = "PLAN_NOT_ELIGIBLE";
    public static final String STATE_CHANGED = "STATE_CHANGED";
    public static final String REFUND_REQUIRED_MANUAL = "REFUND_REQUIRED_MANUAL";
}
