package com.agafari.com.notifications;

/** AGF SUB-R2-09: a sender knows the notice did not leave. Any other exception means the outcome is uncertain. */
public class NotificationNotSentException extends Exception {

    private final boolean retryable;

    public NotificationNotSentException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
