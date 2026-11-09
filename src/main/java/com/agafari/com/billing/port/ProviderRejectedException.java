package com.agafari.com.billing.port;

/** AGF SUB-R4-04: a definite refusal from the provider. */
public class ProviderRejectedException extends RuntimeException {

    public ProviderRejectedException(String message) {
        super(message);
    }
}
