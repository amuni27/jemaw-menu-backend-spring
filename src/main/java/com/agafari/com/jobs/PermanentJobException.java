package com.agafari.com.jobs;

/** AGF SUB-R2-08: a failure that retrying won't fix. */
public class PermanentJobException extends RuntimeException {
    public PermanentJobException(String message, Throwable cause) {
        super(message, cause);
    }
}
