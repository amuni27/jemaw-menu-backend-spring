package com.agafari.com.config;

import java.util.List;

/** AGF SUB-R2-12: the configured flags can't run together; the application refuses to start. */
public class FlagMatrixViolationException extends IllegalStateException {
    public FlagMatrixViolationException(List<String> violations) {
        super("Invalid feature flag combination: " + String.join("; ", violations));
    }
}
