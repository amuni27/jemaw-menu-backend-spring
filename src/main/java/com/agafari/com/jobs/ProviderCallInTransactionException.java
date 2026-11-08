package com.agafari.com.jobs;

/** AGF SUB-R2-08 (BD-21): a payment provider must never be called while a database transaction is open. */
public class ProviderCallInTransactionException extends RuntimeException {
    public ProviderCallInTransactionException() {
        super("A payment provider can't be called inside a database transaction");
    }
}
