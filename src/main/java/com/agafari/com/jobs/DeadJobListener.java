package com.agafari.com.jobs;

import java.time.Instant;

/**
 * AGF gate 2 (register §98 G2-J): told about a job that has just become {@code DEAD}, <b>inside the transaction of that
 * update</b>, on both paths (a failed execution and lost-worker recovery). If a listener throws, the DEAD update rolls back
 * with it: the transition and what the listener records are atomic. The {@code jobs} package stays free of billing: the
 * listener decides what, if anything, a dead job means.
 */
public interface DeadJobListener {

    /** How the job became DEAD. */
    enum Path { EXECUTION, LOST_WORKER }

    /** The dead job as the DEAD update left it. */
    record DeadJob(String id, String type, String key, String payload, int claims, Instant firstAttemptAt, String lastError, String lastErrorKind, Path path) {
    }

    void onDead(DeadJob job);
}
