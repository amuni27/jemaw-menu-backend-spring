package com.agafari.com.jobs;

/** AGF SUB-R2-08 (BD-18): an existing job key was enqueued with a different payload; refused loudly, never dropped. */
public class JobKeyConflict extends RuntimeException {
    public JobKeyConflict(String type, String key) {
        super("Job " + type + "/" + key + " already exists with a different payload");
    }
}
