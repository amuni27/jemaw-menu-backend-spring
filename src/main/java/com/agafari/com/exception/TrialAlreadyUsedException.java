package com.agafari.com.exception;

/** AGF E4-M5: a restaurant can have at most one trial. */
public class TrialAlreadyUsedException extends RuntimeException {

    public static final String CODE = "TRIAL_ALREADY_USED";

    public TrialAlreadyUsedException() {
        super("This restaurant has already had a trial.");
    }
}
