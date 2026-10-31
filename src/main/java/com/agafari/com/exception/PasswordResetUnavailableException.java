package com.agafari.com.exception;

/** Password reset is not configured on this server (AGF-S2-13A: no valid APP_PASSWORD_RESET_SECRET). */
public class PasswordResetUnavailableException extends RuntimeException {

    public static final String CODE = "RESET_UNAVAILABLE";
    public static final String MESSAGE = "Password reset is not available right now. Please contact support.";

    public PasswordResetUnavailableException() {
        super(MESSAGE);
    }
}
