package com.agafari.com.exception;

import org.springframework.http.HttpStatus;

/** AGF SUB-R4-05: a checkout that can't be created, with its status and stable code. */
public class CheckoutRefusedException extends RuntimeException {

    public static final String KEY_REUSED = "IDEMPOTENCY_KEY_REUSED";
    public static final String ACTIVE = "CHECKOUT_ACTIVE";
    public static final String VERSION_CONFLICT = "CHECKOUT_VERSION_CONFLICT";
    public static final String NOT_CANCELLABLE = "CHECKOUT_NOT_CANCELLABLE";
    public static final String CANCELLED = "CHECKOUT_CANCELLED";
    public static final String CREATE_FAILED = "CHECKOUT_CREATE_FAILED";

    private final HttpStatus status;
    private final String code;

    public CheckoutRefusedException(HttpStatus status, String code, String message) {
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
