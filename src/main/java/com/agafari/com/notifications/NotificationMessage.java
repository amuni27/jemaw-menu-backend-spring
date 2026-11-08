package com.agafari.com.notifications;

/** AGF SUB-R2-09: what a sender delivers. {@code id} is stable across retries, so it can serve as a provider idempotency key. */
public record NotificationMessage(String id, String businessId, String kind, String key, NotificationChannel channel,
                                  NotificationKindClass kindClass, String templateData) {
}
