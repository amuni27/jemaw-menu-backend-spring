package com.agafari.com.jobs;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/** AGF SUB-R2-08 (BD-21): every payment provider client method calls this first. */
public final class ProviderCallGuard {

    private ProviderCallGuard() {
    }

    public static void assertNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new ProviderCallInTransactionException();
    }
}
