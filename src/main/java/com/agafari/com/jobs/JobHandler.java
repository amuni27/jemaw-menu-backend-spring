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
     * AGF R4-16-D1: for a job type that serves several providers, a scalar SQL expression over the job row {@code j} that
     * yields one job's provider from trusted persisted data (null when it can't be resolved), so the per-provider pause
     * applies per job. A constant written in code, never built from input. Ignored when {@link #provider()} is set; null
     * (the default) means the type has no provider.
     */
    default String providerSql() {
        return null;
    }

    /**
     * The transient-error time cap of a non-financial job type; null means {@link JobFailurePolicy#NON_FINANCIAL_TRANSIENT_CAP}.
     * Financial types always use {@link JobFailurePolicy#FINANCIAL_TRANSIENT_CAP}. The eight-claim limit applies either way.
     */
    default Duration transientCap() {
        return null;
    }
}
