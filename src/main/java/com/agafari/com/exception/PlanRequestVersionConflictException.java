package com.agafari.com.exception;

/** AGF E4-M4: the plan request changed or was resolved since it was loaded. */
public class PlanRequestVersionConflictException extends RuntimeException {

    public static final String CODE = "PLAN_REQUEST_VERSION_CONFLICT";

    public PlanRequestVersionConflictException() {
        super("The plan request was changed by someone else. Reload to see the latest version.");
    }
}
