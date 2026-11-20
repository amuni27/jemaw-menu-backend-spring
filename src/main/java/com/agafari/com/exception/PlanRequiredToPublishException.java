package com.agafari.com.exception;

/** AGF BE-4 (register §52 B-8): publishing refused while the publish gate is on and the restaurant has no entitled plan. */
public class PlanRequiredToPublishException extends RuntimeException {

    public static final String CODE = "PLAN_REQUIRED_TO_PUBLISH";
    public static final String MESSAGE = "Choose a plan to publish your menu. You can keep editing it meanwhile.";

    public PlanRequiredToPublishException() {
        super(MESSAGE);
    }
}
