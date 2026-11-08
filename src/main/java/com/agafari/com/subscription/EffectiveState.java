package com.agafari.com.subscription;

import com.agafari.com.jpa.entity.BusinessSubscription;

import java.time.Instant;

import static com.agafari.com.subscription.LifecycleStatus.EXPIRED;
import static com.agafari.com.subscription.LifecycleStatus.PAST_DUE;
import static com.agafari.com.subscription.LifecycleStatus.TRIAL;

/**
 * AGF SUB-R2-01: the read-time effective state of one subscription row (TA Rev. 3 §4.3; Backend Detailed Design Rev. 2
 * §3.2). Pure: no database, provider, clock, Spring or job access; callers pass every input, including "now".
 * <ol>
 *   <li>A stored EXPIRED row is ended.</li>
 *   <li>TRIAL: ended at {@code period_end} (exactly the existing rule, {@code BusinessSubscription.effectiveStatus}); no grace;
 *   independent of the period-end flag.</li>
 *   <li>An approved grandfathering end, as computed by the existing {@code GrandfatheringService.effectivelyEnded}
 *   (E4-M6, unchanged) and passed in by the caller, ends any non-trial row.</li>
 *   <li>With {@code period-end-enabled} off, non-trial rows keep their stored status (today's behaviour).</li>
 *   <li>MANUAL and OWNER: an explicit end date ends the row exactly at it (R-4); a reviewed open-ended MANUAL row and a row
 *   without an end date stay as stored.</li>
 *   <li>PROVIDER: grace anchored at {@code period_end}: from it until {@code period_end + grace} the row is PAST_DUE (still
 *   entitled), from the grace end on it is ended. A PROVIDER row without a period end is never entitled.</li>
 * </ol>
 * Wired into entitlements and {@code materialize} through {@link EffectiveStateEvaluator} (SUB-R2-07).
 */
public final class EffectiveState {

    private EffectiveState() {
    }

    public static EffectiveView of(SubscriptionSnapshot s, GraceSettings grace, boolean periodEndEnabled,
                                   boolean grandfatheringEnded, Instant now) {
        if (s == null || grace == null || now == null) throw new IllegalArgumentException("Snapshot, grace and time are required");
        Instant end = s.periodEnd();
        Instant graceUntil = s.source() == SubscriptionSource.PROVIDER && end != null ? end.plus(grace.providerGrace()) : null;

        if (s.status() == EXPIRED) return view(EXPIRED, end, graceUntil, "STORED_EXPIRED");

        if (s.source() == SubscriptionSource.TRIAL) {
            if (s.status() == TRIAL && end != null && !end.isAfter(now)) return view(EXPIRED, end, null, "TRIAL_ENDED");
            return view(s.status(), end, null, s.status() == TRIAL ? "TRIAL_RUNNING" : "STORED");
        }

        if (grandfatheringEnded) return view(EXPIRED, end, graceUntil, "GRANDFATHERING_ENDED");
        if (!periodEndEnabled) return view(s.status(), end, graceUntil, "STORED");

        return switch (s.source()) {
            case MANUAL, OWNER -> {
                if (s.openEnded()) yield view(s.status(), end, null, "OPEN_ENDED");
                if (end != null && !end.isAfter(now)) yield view(EXPIRED, end, null, "PERIOD_END_PASSED");
                yield view(s.status(), end, null, "STORED");
            }
            case PROVIDER -> {
                if (end == null) yield view(EXPIRED, null, null, "PROVIDER_WITHOUT_PERIOD");
                if (now.isBefore(end)) yield view(s.status(), end, graceUntil, "STORED");
                if (now.isBefore(graceUntil)) yield view(PAST_DUE, end, graceUntil, "IN_GRACE");
                yield view(EXPIRED, end, graceUntil, "GRACE_ENDED");
            }
            case TRIAL -> throw new IllegalStateException("handled above");
        };
    }

    /** Reads an existing entity without changing it. */
    public static EffectiveView of(BusinessSubscription s, GraceSettings grace, boolean periodEndEnabled,
                                   boolean grandfatheringEnded, Instant now) {
        if (s == null) throw new IllegalArgumentException("Subscription is required");
        return of(SubscriptionSnapshot.of(s), grace, periodEndEnabled, grandfatheringEnded, now);
    }

    private static EffectiveView view(LifecycleStatus status, Instant periodEnd, Instant graceUntil, String reason) {
        return new EffectiveView(status, periodEnd, graceUntil, status.isCurrent(), reason);
    }
}
