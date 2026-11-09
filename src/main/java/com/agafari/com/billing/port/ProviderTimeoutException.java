package com.agafari.com.billing.port;

/** AGF SUB-R4-04: the provider's answer was lost; the call may or may not have taken effect. */
public class ProviderTimeoutException extends RuntimeException {

    public ProviderTimeoutException(String message) {
        super(message);
    }
}
