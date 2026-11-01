package com.agafari.com.exception;

/** AGF E4-M3: the subscription changed since the admin loaded it (stale rowVersion or a concurrent change). */
public class SubscriptionVersionConflictException extends RuntimeException {

    public static final String CODE = "SUBSCRIPTION_VERSION_CONFLICT";

    public SubscriptionVersionConflictException() {
        super("The subscription was changed by someone else. Reload to see the latest version.");
    }
}
