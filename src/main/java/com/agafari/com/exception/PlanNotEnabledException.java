package com.agafari.com.exception;

/** AGF E4-M4: the requested plan is no longer enabled by the current monetization configuration. */
public class PlanNotEnabledException extends RuntimeException {

    public static final String CODE = "PLAN_NOT_ENABLED";

    public PlanNotEnabledException() {
        super("This plan isn't enabled by the current configuration. Decline the request.");
    }
}
