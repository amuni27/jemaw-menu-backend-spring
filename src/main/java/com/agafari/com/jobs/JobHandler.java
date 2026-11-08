package com.agafari.com.jobs;

import java.time.Duration;

/**
 * AGF SUB-R2-08: the work behind one job type. Runs outside any transaction and must be idempotent by the job's key; a
 * handler opens its own short transactions.
 */
public interface JobHandler<T> {

    String type();

    Class<T> payloadType();

    void handle(T payload) throws Exception;

    /** The provider this job calls, for the per-provider pause; null if none. */
    default String provider() {
        return null;
    }

    /**
     * The transient-error time cap of a non-financial job type; null means none. Financial types always use
     * {@link JobFailurePolicy#FINANCIAL_TRANSIENT_CAP}.
     */
    default Duration transientCap() {
        return null;
    }
}
