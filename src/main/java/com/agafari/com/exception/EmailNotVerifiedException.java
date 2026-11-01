package com.agafari.com.exception;

/** Publishing refused while the publish gate is on and the owner's email is not verified (AGF-S2-22). */
public class EmailNotVerifiedException extends RuntimeException {

    public static final String CODE = "EMAIL_NOT_VERIFIED";
    public static final String MESSAGE = "Verify your email to publish menus.";

    public EmailNotVerifiedException() {
        super(MESSAGE);
    }
}
