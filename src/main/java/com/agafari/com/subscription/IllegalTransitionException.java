package com.agafari.com.subscription;

/** AGF SUB-R2-01: a lifecycle change that the transition table doesn't permit. */
public class IllegalTransitionException extends RuntimeException {

    public static final String CODE = "SUBSCRIPTION_TRANSITION_NOT_ALLOWED";

    public IllegalTransitionException(String message) {
        super(message);
    }
}
