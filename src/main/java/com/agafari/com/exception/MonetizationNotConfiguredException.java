package com.agafari.com.exception;

/** AGF E4-M3: a subscription needs a current monetization configuration. */
public class MonetizationNotConfiguredException extends RuntimeException {

    public static final String CODE = "MONETIZATION_NOT_CONFIGURED";

    public MonetizationNotConfiguredException() {
        super("Set up the monetization configuration first.");
    }
}
