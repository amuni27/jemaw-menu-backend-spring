package com.agafari.com.exception;

/** A reset link that is tampered with, expired or already used (AGF-S2-13A). The message never says which. */
public class PasswordResetLinkException extends RuntimeException {

    public static final String CODE = "RESET_LINK_INVALID";
    public static final String MESSAGE = "This reset link is invalid or has expired. Request a new link.";

    public PasswordResetLinkException() {
        super(MESSAGE);
    }
}
