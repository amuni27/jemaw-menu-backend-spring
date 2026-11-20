package com.agafari.com.subscription;

/**
 * AGF SUB-R5-03, AD-7 (register §63) and D-R6 (§64): payment grace for PROVIDER subscriptions is <b>7 calendar days</b> from
 * the recorded {@code period_end}, in both markets. It is recorded on every new PROVIDER row at creation and never changed
 * (the V20 trigger keeps it immutable); trials, MANUAL and OWNER rows get none. Enforced in code only (D-R6): a database
 * check is reconsidered in the activation gate review. Rows created before R5-03 keep what they recorded (null = no grace).
 */
public final class ProviderGrace {

    public static final int DAYS = 7;

    private ProviderGrace() {
    }

    /** The grace a new PROVIDER row records: {@link #DAYS}. Unset means AD-7; any other value is refused (no other duration may be invented). */
    public static int require(Integer requested) {
        if (requested != null && requested != DAYS) throw new IllegalArgumentException("AD-7: PROVIDER grace is " + DAYS + " days; " + requested + " is not allowed");
        return DAYS;
    }
}
