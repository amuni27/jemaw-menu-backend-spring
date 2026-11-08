package com.agafari.com.subscription;

import java.util.Set;

/**
 * AGF SUB-R2-01: one permitted row of the lifecycle transition table. {@code from == null} means "no row yet" (a new row is
 * created); every other transition changes an existing row.
 */
public record Transition(LifecycleStatus from, LifecycleStatus to, TransitionReason reason, Set<SubscriptionSource> sources) {

    public Transition {
        if (to == null || reason == null || sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("A transition needs a target status, a reason and at least one source");
        }
        sources = Set.copyOf(sources);
    }

    public boolean isCreation() {
        return from == null;
    }
}
