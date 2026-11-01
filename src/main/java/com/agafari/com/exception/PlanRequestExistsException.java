package com.agafari.com.exception;

/** AGF E4-M4: the restaurant already has a pending plan request (one per restaurant). */
public class PlanRequestExistsException extends RuntimeException {

    public static final String CODE = "PLAN_REQUEST_EXISTS";

    public PlanRequestExistsException() {
        super("Your restaurant already has a pending plan request.");
    }
}
