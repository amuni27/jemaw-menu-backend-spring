package com.agafari.com.exception;

/** AGF E4-M4: a manual record is refused while the restaurant has a pending plan request (invariant I1). */
public class PlanRequestPendingException extends RuntimeException {

    public static final String CODE = "PLAN_REQUEST_PENDING";

    public PlanRequestPendingException() {
        super("This restaurant has a pending plan request. Fulfil or decline it first.");
    }
}
