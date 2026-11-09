package com.agafari.com.subscription;

/** AGF SUB-R4-09 (DD §4.1): the current subscription isn't the one this payment expected (matrix row 6). */
public class ExpectedSubscriptionChangedException extends RuntimeException {

    public ExpectedSubscriptionChangedException() {
        super("The current subscription isn't the expected one");
    }
}
