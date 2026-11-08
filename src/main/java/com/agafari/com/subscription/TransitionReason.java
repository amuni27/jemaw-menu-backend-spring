package com.agafari.com.subscription;

/**
 * AGF SUB-R2-01: why a lifecycle transition happens (Backend Detailed Design Rev. 2 §2.2; TA Rev. 3 §3.1 {@code ended_reason}).
 * {@link #requiresSuccessor()} marks the endings that only happen together with a new row (end + new row, TA §4.1);
 * the successor itself is created by a separate creation transition.
 */
public enum TransitionReason {
    // creation of a new row
    REGISTRATION_TRIAL(false, false),
    OWNER_FREE(false, false),
    ADMIN_RECORD(false, false),
    VERIFIED_PAYMENT(false, false),
    // same row, same contract
    VERIFIED_RENEWAL(false, false),
    ADMIN_PERIOD_CHANGE(false, false),
    RENEWAL_FAILED(false, false),
    // endings (ended_reason values)
    TRIAL_END(true, false),
    CONVERTED(true, true),
    CHANGED(true, true),
    CANCELLED(true, false),
    PERIOD_END(true, false),
    PAYMENT_FAILED(true, false),
    ADMIN(true, false),
    GRANDFATHERED_END(true, false),
    REFUNDED(true, false),
    DISPUTE_LOST(true, false);

    private final boolean ending;
    private final boolean successor;

    TransitionReason(boolean ending, boolean successor) {
        this.ending = ending;
        this.successor = successor;
    }

    /** True if the transition ends the row (to EXPIRED). */
    public boolean isEnding() {
        return ending;
    }

    /** True if the ending is only valid together with a successor row (conversion or plan change). */
    public boolean requiresSuccessor() {
        return successor;
    }
}
