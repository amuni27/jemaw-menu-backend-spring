package com.agafari.com.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** AGF SUB-R2-08 (DD §9.2): what happens after a failed run. Pure. */
public final class JobFailurePolicy {

    public static final int MAX_CLAIMS = 8;
    public static final Duration FINANCIAL_TRANSIENT_CAP = Duration.ofHours(72);
    private static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2), Duration.ofHours(6));

    public enum Kind { TRANSIENT, PERMANENT }

    /** {@code retryAfter} is null when the job is dead. */
    public record Decision(boolean dead, Duration retryAfter) {
    }

    private JobFailurePolicy() {
    }

    /** Anything not declared transient counts as permanent (DD: "PERMANENT or unknown"). */
    public static Kind classify(Throwable t) {
        return t instanceof TransientJobException ? Kind.TRANSIENT : Kind.PERMANENT;
    }

    /** Financial jobs have the fixed transient cap, and their keys are never pruned. */
    public static boolean isFinancial(String type) {
        return type.startsWith("refund.") || type.startsWith("checkout.") || type.equals("inbox.process");
    }

    public static Duration backoff(int claims) {
        return BACKOFF.get(Math.min(Math.max(claims, 1), BACKOFF.size()) - 1);
    }

    public static Decision decide(Kind kind, int claims, Instant firstAttemptAt, Instant now, Duration transientCap) {
        boolean dead = kind == Kind.TRANSIENT ? transientCap != null && !now.isBefore(firstAttemptAt.plus(transientCap)) : claims >= MAX_CLAIMS;
        return new Decision(dead, dead ? null : backoff(claims));
    }
}
