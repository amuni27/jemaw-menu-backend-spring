package com.agafari.com.jobs;

/** AGF SUB-R2-08: a failure worth retrying with backoff (timeouts, 5xx, 429). */
public class TransientJobException extends RuntimeException {
    public TransientJobException(String message, Throwable cause) {
        super(message, cause);
    }
}
