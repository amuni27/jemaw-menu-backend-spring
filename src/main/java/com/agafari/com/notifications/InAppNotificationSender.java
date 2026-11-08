package com.agafari.com.notifications;

import org.springframework.stereotype.Component;

/** AGF SUB-R2-09: an in-app notice is the row itself; marking it SENT is what shows it. Nothing leaves the system. */
@Component
public class InAppNotificationSender implements NotificationSender {

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.IN_APP;
    }

    @Override
    public boolean retrySafeAfterUncertainOutcome() {
        return true;
    }

    @Override
    public void deliver(NotificationMessage message) {
    }
}
