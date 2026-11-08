package com.agafari.com.notifications;

/** AGF SUB-R2-09: the approved notice kinds and their class (story SUB-R2-09, I1). */
public enum NotificationKind {
    RENEWAL_PAYMENT_REQUEST(NotificationKindClass.PAYMENT_REQUEST),
    EXPIRY_NOTICE(NotificationKindClass.ESSENTIAL),
    REVIEW_STATUS(NotificationKindClass.ESSENTIAL);

    private final NotificationKindClass kindClass;

    NotificationKind(NotificationKindClass kindClass) {
        this.kindClass = kindClass;
    }

    public NotificationKindClass kindClass() {
        return kindClass;
    }
}
