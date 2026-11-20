package com.agafari.com.exception;

/** AGF BE-6 (register §56): a dry-run sign-off that can't be recorded (409 with a stable code). */
public class PublishDryRunRefusedException extends RuntimeException {

    public static final String BLOCKED = "DRY_RUN_BLOCKED";
    public static final String CHANGED = "DRY_RUN_CHANGED";

    private final String code;

    public PublishDryRunRefusedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
