package com.agafari.com.billing;

/**
 * AGF SUB-R5-03 (addendum §10): what the renewal rows decided, and nothing else; {@link RenewalOutcomes} applies it.
 *
 * @param row    R1–R9
 * @param code   the exception or quarantine code; null for an extension, a retry or an applied invoice
 * @param cause  the row's own code when {@code code} is the manual-refund fallback; otherwise equal to {@code code}
 * @param refund how the verified charge's money is handled when the renewal isn't applied
 */
public record RenewalOutcome(Action action, String row, String code, String cause, Refund refund) {

    public enum Action { EXTEND, ALREADY_DONE, QUARANTINE, RETRY, EXCEPTION, AUTO_REFUND }

    /** P-R4: configuration-type mismatches are held for review; duplicates and payments after expiry are refunded when safe. */
    public enum Refund { NONE, MANUAL_HOLD, AUTOMATIC, MANUAL_FALLBACK }

    public static final String UNKNOWN_SUBSCRIPTION = "RENEWAL_UNKNOWN_SUBSCRIPTION";
    public static final String CUSTOMER_MISMATCH = "RENEWAL_CUSTOMER_MISMATCH";
    public static final String EVIDENCE_NOT_QUALIFYING = "RENEWAL_EVIDENCE_NOT_QUALIFYING";
    public static final String AMOUNT_MISMATCH = "RENEWAL_AMOUNT_MISMATCH";
    public static final String PERIOD_MISMATCH = "RENEWAL_PERIOD_MISMATCH";
    public static final String PENDING_ORDER = "RENEWAL_PENDING_ORDER";
    public static final String DUPLICATE_PERIOD = "RENEWAL_DUPLICATE_PERIOD";
    public static final String PAID_AFTER_EXPIRY = "PAID_AFTER_EXPIRY";
    /** A payment made by the grace end whose row had already been ended (the sweep and webhook both missed the window). */
    public static final String ROW_ENDED = "RENEWAL_ROW_ENDED";
}
