package com.agafari.com.notifications;

/** AGF SUB-R2-09: the approved notice kinds and their class (story SUB-R2-09, I1); AGF SUB-R5-03-D adds two (N3, register §69). */
public enum NotificationKind {
    RENEWAL_PAYMENT_REQUEST(NotificationKindClass.PAYMENT_REQUEST),
    EXPIRY_NOTICE(NotificationKindClass.ESSENTIAL),
    REVIEW_STATUS(NotificationKindClass.ESSENTIAL),
    /** A failed renewal was paid within grace. */
    PAYMENT_RECEIVED(NotificationKindClass.ESSENTIAL),
    /** The plan had ended when the renewal was paid; the payment will be refunded. */
    PLAN_ENDED_REFUND(NotificationKindClass.ESSENTIAL);

    private final NotificationKindClass kindClass;

    NotificationKind(NotificationKindClass kindClass) {
        this.kindClass = kindClass;
    }

    public NotificationKindClass kindClass() {
        return kindClass;
    }
}
