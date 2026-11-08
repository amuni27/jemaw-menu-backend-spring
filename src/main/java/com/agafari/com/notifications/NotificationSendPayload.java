package com.agafari.com.notifications;

/** AGF SUB-R2-09: the {@code notification.send} job payload. */
public record NotificationSendPayload(String notificationId, String jobKey) {
}
