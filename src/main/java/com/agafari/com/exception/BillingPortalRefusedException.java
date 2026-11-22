package com.agafari.com.exception;

import org.springframework.http.HttpStatus;

/** AGF SUB-R5-03-E: the card-update page can't be opened, with its status and stable code (never the provider's message). */
public class BillingPortalRefusedException extends RuntimeException {

    public static final String NOT_ELIGIBLE = "PORTAL_NOT_ELIGIBLE";
    public static final String UNAVAILABLE = "PORTAL_UNAVAILABLE";

    private final HttpStatus status;
    private final String code;

    public BillingPortalRefusedException(HttpStatus status, String code, String message) {
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
