package com.agafari.com.exception;

/** AGF E4-M5: the trial has already ended (effectively or persisted), so it can't be extended or changed. */
public class TrialEndedException extends RuntimeException {

    public static final String CODE = "TRIAL_ENDED";

    public TrialEndedException() {
        super("This trial has already ended.");
    }
}
