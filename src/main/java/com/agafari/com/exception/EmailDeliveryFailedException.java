package com.agafari.com.exception;

/** The email service did not accept a verification link (resend); the owner is told honestly (AGF-S2-22). */
public class EmailDeliveryFailedException extends RuntimeException {

    public static final String CODE = "EMAIL_DELIVERY_FAILED";
    public static final String MESSAGE = "We couldn't send the email right now. Please try again in a few minutes.";

    public EmailDeliveryFailedException() {
        super(MESSAGE);
    }
}
