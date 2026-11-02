package com.agafari.com.exception;

/** AGF E4-M8: a suspended ad can't be paused or resumed by its owner; only the Super Admin reinstates it. */
public class AdSuspendedException extends RuntimeException {

    public static final String CODE = "AD_SUSPENDED";

    public AdSuspendedException() {
        super("This ad was suspended by Agafari. You can edit or archive it; only Agafari can reinstate it.");
    }
}
