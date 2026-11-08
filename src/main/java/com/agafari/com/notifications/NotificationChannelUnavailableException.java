package com.agafari.com.notifications;

/** AGF SUB-R2-09: no sender exists for this channel (email waits for D5). */
public class NotificationChannelUnavailableException extends RuntimeException {
    public NotificationChannelUnavailableException(NotificationChannel channel) {
        super("No sender is available for the " + channel + " channel");
    }
}
