package com.agafari.com.exception;

/** AGF E4-M3: the restaurant already has a current subscription (one per restaurant). */
public class SubscriptionExistsException extends RuntimeException {

    public static final String CODE = "SUBSCRIPTION_EXISTS";

    public SubscriptionExistsException() {
        super("This restaurant already has a current subscription. End it before recording a new one.");
    }
}
