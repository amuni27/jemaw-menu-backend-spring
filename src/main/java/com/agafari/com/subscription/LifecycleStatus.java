package com.agafari.com.subscription;

import java.util.Locale;

/**
 * AGF SUB-R2-01: the stored statuses of a {@code business_subscription} row, as allowed by its status check today
 * (TRIAL, ACTIVE, GRANDFATHERED, EXPIRED) plus PAST_DUE from the target architecture (TA §3.1, arrives with V20).
 * GRANDFATHERED is a legal stored value but grandfathering itself stays computed (E4-M6).
 */
public enum LifecycleStatus {
    TRIAL, ACTIVE, PAST_DUE, GRANDFATHERED, EXPIRED;

    public static LifecycleStatus of(String stored) {
        return valueOf(stored.toUpperCase(Locale.ROOT));
    }

    /** True for every status except EXPIRED (the one-current index counts these rows as current). */
    public boolean isCurrent() {
        return this != EXPIRED;
    }
}
