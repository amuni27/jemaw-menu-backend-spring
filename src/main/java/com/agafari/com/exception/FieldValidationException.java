package com.agafari.com.exception;

import java.util.Map;

/** AGF-S2-12 CR1: a rule that needs stored data (e.g. the restaurant's country) failed; answered like S1-10 field errors. */
public class FieldValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public FieldValidationException(Map<String, String> fieldErrors) {
        super("Validation failed");
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
