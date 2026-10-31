package com.agafari.com.exception;

/** The account exists but is not ACTIVE (SUSPENDED or INACTIVE): 403 with code ACCOUNT_INACTIVE (AGF-S1-07). */
public class AccountInactiveException extends RuntimeException {

    public static final String CODE = "ACCOUNT_INACTIVE";
    public static final String MESSAGE = "Account is not active. Contact support.";

    public AccountInactiveException() {
        super(MESSAGE);
    }
}
