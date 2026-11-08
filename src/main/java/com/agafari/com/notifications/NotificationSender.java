package com.agafari.com.notifications;

/** AGF SUB-R2-09: one delivery channel. Provider-neutral; no external sender exists until D5. */
public interface NotificationSender {

    NotificationChannel channel();

    /**
     * True only if a repeat send after an uncertain outcome can't reach the recipient twice, a capability that must be
     * verified for the provider. Defaults to false.
     */
    default boolean retrySafeAfterUncertainOutcome() {
        return false;
    }

    void deliver(NotificationMessage message) throws Exception;
}
