package com.agafari.com.subscription;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.agafari.com.subscription.LifecycleStatus.ACTIVE;
import static com.agafari.com.subscription.LifecycleStatus.EXPIRED;
import static com.agafari.com.subscription.LifecycleStatus.GRANDFATHERED;
import static com.agafari.com.subscription.LifecycleStatus.PAST_DUE;
import static com.agafari.com.subscription.LifecycleStatus.TRIAL;
import static com.agafari.com.subscription.SubscriptionSource.MANUAL;
import static com.agafari.com.subscription.SubscriptionSource.OWNER;
import static com.agafari.com.subscription.SubscriptionSource.PROVIDER;
import static com.agafari.com.subscription.TransitionReason.*;

/**
 * AGF SUB-R2-01: the single table of permitted subscription lifecycle transitions (Backend Detailed Design Rev. 2 §2.2;
 * TA Rev. 3 §3.1 and §4.1–4.2). Pure; nothing here reads or writes the database.
 * <ul>
 *   <li>One row = one contract: a plan or source change is an ending ({@link TransitionReason#CONVERTED} /
 *   {@link TransitionReason#CHANGED}) <b>plus</b> the creation of a successor row, checked together by
 *   {@link #requireReplacement}. A trial therefore never becomes ACTIVE on the same row.</li>
 *   <li>The status changes of existing rows ({@link #statusChangesOfExistingRows()}) are the ones the future V20 guard
 *   permits; a test compares them so the two can't drift apart.</li>
 *   <li>Super Admin period changes stay available for MANUAL, TRIAL and OWNER rows (R-4); PROVIDER periods move only by a
 *   verified renewal.</li>
 * </ul>
 * Not used by any service yet (SUB-R2-02 onwards wires it in).
 */
public final class TransitionRules {

    private static final Set<SubscriptionSource> NON_TRIAL = Set.of(MANUAL, OWNER, PROVIDER);

    private static final List<Transition> TABLE = List.of(
            // creation of a new row (from = none)
            new Transition(null, TRIAL, REGISTRATION_TRIAL, Set.of(SubscriptionSource.TRIAL)),
            new Transition(null, ACTIVE, OWNER_FREE, Set.of(OWNER)),
            new Transition(null, ACTIVE, ADMIN_RECORD, Set.of(MANUAL)),
            new Transition(null, ACTIVE, VERIFIED_PAYMENT, Set.of(PROVIDER)),
            // same row, same contract
            new Transition(ACTIVE, ACTIVE, VERIFIED_RENEWAL, Set.of(PROVIDER)),
            new Transition(ACTIVE, ACTIVE, ADMIN_PERIOD_CHANGE, Set.of(MANUAL, OWNER)),
            new Transition(TRIAL, TRIAL, ADMIN_PERIOD_CHANGE, Set.of(SubscriptionSource.TRIAL)),
            new Transition(ACTIVE, PAST_DUE, RENEWAL_FAILED, Set.of(PROVIDER)),
            new Transition(PAST_DUE, ACTIVE, VERIFIED_RENEWAL, Set.of(PROVIDER)),
            // endings (ended_reason)
            new Transition(TRIAL, EXPIRED, TRIAL_END, Set.of(SubscriptionSource.TRIAL)),
            new Transition(TRIAL, EXPIRED, CONVERTED, Set.of(SubscriptionSource.TRIAL)),
            new Transition(TRIAL, EXPIRED, ADMIN, Set.of(SubscriptionSource.TRIAL)),
            new Transition(ACTIVE, EXPIRED, CANCELLED, Set.of(PROVIDER)),
            new Transition(ACTIVE, EXPIRED, CHANGED, NON_TRIAL),
            new Transition(ACTIVE, EXPIRED, PERIOD_END, Set.of(MANUAL, OWNER)),
            new Transition(ACTIVE, EXPIRED, ADMIN, NON_TRIAL),
            new Transition(ACTIVE, EXPIRED, GRANDFATHERED_END, NON_TRIAL),
            new Transition(ACTIVE, EXPIRED, REFUNDED, Set.of(PROVIDER)),
            new Transition(ACTIVE, EXPIRED, DISPUTE_LOST, Set.of(PROVIDER)),
            new Transition(PAST_DUE, EXPIRED, PAYMENT_FAILED, Set.of(PROVIDER)),
            // AGF SUB-R4-15 (PO D2): a paid downgrade funded within recorded grace replaces the PAST_DUE row at its boundary
            new Transition(PAST_DUE, EXPIRED, CHANGED, Set.of(PROVIDER)),
            new Transition(PAST_DUE, EXPIRED, ADMIN, Set.of(PROVIDER)),
            new Transition(PAST_DUE, EXPIRED, REFUNDED, Set.of(PROVIDER)),
            new Transition(PAST_DUE, EXPIRED, DISPUTE_LOST, Set.of(PROVIDER)),
            // a stored GRANDFATHERED value (legal in the status check, not written today) can only be ended
            new Transition(GRANDFATHERED, EXPIRED, GRANDFATHERED_END, NON_TRIAL),
            new Transition(GRANDFATHERED, EXPIRED, ADMIN, NON_TRIAL));

    private TransitionRules() {
    }

    /** The complete table (immutable). */
    public static List<Transition> table() {
        return TABLE;
    }

    /** True if the table permits this step; {@code from == null} means creating a new row. */
    public static boolean isAllowed(LifecycleStatus from, LifecycleStatus to, TransitionReason reason, SubscriptionSource source) {
        if (to == null || reason == null || source == null) return false;
        return TABLE.stream().anyMatch(t -> t.from() == from && t.to() == to && t.reason() == reason && t.sources().contains(source));
    }

    /**
     * Refuses a step the table doesn't permit, and an ending that needs a successor (conversion, plan change): those are
     * only valid through {@link #requireReplacement}.
     */
    public static void require(LifecycleStatus from, LifecycleStatus to, TransitionReason reason, SubscriptionSource source) {
        if (!isAllowed(from, to, reason, source)) {
            throw new IllegalTransitionException("A subscription can't go from " + label(from) + " to " + to + " (" + reason + ", " + source + ")");
        }
        if (reason.requiresSuccessor()) {
            throw new IllegalTransitionException("Ending a subscription by " + reason + " needs a successor row in the same change");
        }
    }

    /**
     * A replacement: the old row ends ({@code ending} must take a successor) and a new row is created
     * ({@code successorCreation} must be a creation permitted for {@code successorSource}).
     */
    public static void requireReplacement(LifecycleStatus oldStatus, SubscriptionSource oldSource, TransitionReason ending,
                                          TransitionReason successorCreation, SubscriptionSource successorSource) {
        if (ending == null || !ending.requiresSuccessor() || !isAllowed(oldStatus, EXPIRED, ending, oldSource)) {
            throw new IllegalTransitionException("A " + oldSource + " subscription in " + oldStatus + " can't be replaced by " + ending);
        }
        boolean creation = TABLE.stream().anyMatch(t -> t.isCreation() && t.reason() == successorCreation && t.sources().contains(successorSource));
        if (!creation) {
            throw new IllegalTransitionException("The successor must be a new " + successorSource + " row created by a permitted reason, not " + successorCreation);
        }
    }

    /** The status changes of existing rows (from ≠ to) that the table permits; mirrors the V20 guard (TA Rev. 3 §3.1). */
    public static Set<List<LifecycleStatus>> statusChangesOfExistingRows() {
        Set<List<LifecycleStatus>> pairs = new HashSet<>();
        for (Transition t : TABLE) {
            if (!t.isCreation() && t.from() != t.to()) pairs.add(List.of(t.from(), t.to()));
        }
        return Set.copyOf(pairs);
    }

    private static String label(LifecycleStatus from) {
        return from == null ? "no subscription" : from.name();
    }
}
