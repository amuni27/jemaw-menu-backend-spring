package com.agafari.com.exception;

/** A verification link that is tampered with, expired, or for an email the account no longer has; the message never says which (AGF-S2-22). */
public class EmailVerificationLinkException extends RuntimeException {

    public static final String CODE = "VERIFY_LINK_INVALID";
    public static final String MESSAGE = "This verification link has expired or is invalid. Sign in to request a new one.";

    public EmailVerificationLinkException() {
        super(MESSAGE);
    }
}
