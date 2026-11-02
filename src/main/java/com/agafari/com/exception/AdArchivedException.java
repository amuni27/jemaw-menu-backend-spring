package com.agafari.com.exception;

/** AGF E4-M7: archived ads are frozen. */
public class AdArchivedException extends RuntimeException {

    public static final String CODE = "AD_ARCHIVED";

    public AdArchivedException() {
        super("This ad is archived and can't be changed.");
    }
}
