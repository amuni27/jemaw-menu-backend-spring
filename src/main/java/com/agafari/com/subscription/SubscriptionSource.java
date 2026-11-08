package com.agafari.com.subscription;

import java.util.Locale;

/** AGF SUB-R2-01: who created a subscription row (immutable per row; V13/V15/V19 source check). */
public enum SubscriptionSource {
    MANUAL, TRIAL, OWNER, PROVIDER;

    public static SubscriptionSource of(String stored) {
        return valueOf(stored.toUpperCase(Locale.ROOT));
    }
}
