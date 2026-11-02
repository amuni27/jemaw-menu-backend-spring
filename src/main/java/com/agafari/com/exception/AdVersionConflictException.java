package com.agafari.com.exception;

/** AGF E4-M7: the ad changed since the admin loaded it. */
public class AdVersionConflictException extends RuntimeException {

    public static final String CODE = "AD_VERSION_CONFLICT";

    public AdVersionConflictException() {
        super("This ad was changed by someone else. Reload and try again.");
    }
}
