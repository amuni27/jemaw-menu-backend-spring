package com.agafari.com.exception;

import org.springframework.http.HttpStatus;

/** AGF SUB-R4-11: an exception action that can't be applied, with its status and stable code. */
public class BillingExceptionRefusedException extends RuntimeException {

    public static final String VERSION_CONFLICT = "BILLING_EXCEPTION_VERSION_CONFLICT";
    public static final String NOT_ALLOWED = "BILLING_EXCEPTION_ACTION_NOT_ALLOWED";
    public static final String NOT_REPLAYABLE = "BILLING_EXCEPTION_NOT_REPLAYABLE";

    private final HttpStatus status;
    private final String code;

    public BillingExceptionRefusedException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
