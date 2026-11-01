package com.agafari.com.exception;

/** Verification can't be used because no email can be delivered (before D5) or no valid secret is set (AGF-S2-22). */
public class EmailVerificationUnavailableException extends RuntimeException {

    public static final String CODE = "EMAIL_VERIFICATION_UNAVAILABLE";
    public static final String MESSAGE = "Email verification isn't available right now.";

    public EmailVerificationUnavailableException() {
        super(MESSAGE);
    }
}
