package com.agafari.com.subscription;

import com.agafari.com.jpa.entity.BusinessSubscription;

import java.time.Instant;

/**
 * AGF SUB-R2-01: the facts of one subscription row that the read-time evaluation needs. {@code openEnded} is the MANUAL
 * "reviewed open-ended" marker of the target design (AD-11, V20); rows read from today's schema are never marked.
 */
public record SubscriptionSnapshot(LifecycleStatus status, SubscriptionSource source, Instant periodEnd, boolean openEnded) {

    public SubscriptionSnapshot {
        if (status == null || source == null) throw new IllegalArgumentException("Status and source are required");
    }

    /** Reads an existing entity without changing it (no open-ended marker exists before V20). */
    public static SubscriptionSnapshot of(BusinessSubscription s) {
        return new SubscriptionSnapshot(LifecycleStatus.of(s.getStatus()), SubscriptionSource.of(s.getSource()), s.getPeriodEnd(), false);
    }
}
