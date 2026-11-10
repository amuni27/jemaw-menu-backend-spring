package com.agafari.com.notifications;

/**
 * AGF SUB-R4-11 (I1): is a payment of this restaurant under review? While it is, {@code PAYMENT_REQUEST} notices are
 * paused; {@code ESSENTIAL} notices continue. Implemented by {@code billing.PaymentReview}.
 */
@FunctionalInterface
public interface PaymentReviewCheck {

    /** For a context without billing: nothing is ever under review. */
    PaymentReviewCheck NONE = businessId -> false;

    boolean underReview(String businessId);
}
