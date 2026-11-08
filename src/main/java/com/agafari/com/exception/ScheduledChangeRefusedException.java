package com.agafari.com.exception;

/** AGF SUB-R2-11: this subscription can't take this scheduled change now. */
public class ScheduledChangeRefusedException extends RuntimeException {

    public static final String CODE = "SCHEDULED_CHANGE_REFUSED";

    public ScheduledChangeRefusedException(String message) {
        super(message);
    }
}
