package com.agafari.com.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** AGF SUB-R2-08 (DD §9.2): what happens after a failed run. Pure. */
public final class JobFailurePolicy {

    public static final int MAX_CLAIMS = 8;
    public static final Duration FINANCIAL_TRANSIENT_CAP = Duration.ofHours(72);
    public static final Duration NON_FINANCIAL_TRANSIENT_CAP = Duration.ofHours(24);
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

    /** Financial types have the fixed cap; another type uses the handler's own, or the default. */
    public static Duration transientCap(String type, Duration declared) {
        if (isFinancial(type)) return FINANCIAL_TRANSIENT_CAP;
        return declared != null ? declared : NON_FINANCIAL_TRANSIENT_CAP;
    }

    public static Duration backoff(int claims) {
        return BACKOFF.get(Math.min(Math.max(claims, 1), BACKOFF.size()) - 1);
    }

    /** BD-18: dead after eight claims or at the transient time cap, whichever comes first. */
    public static Decision decide(Kind kind, int claims, Instant firstAttemptAt, Instant now, Duration transientCap) {
        boolean dead = claims >= MAX_CLAIMS || (kind == Kind.TRANSIENT && !now.isBefore(firstAttemptAt.plus(transientCap)));
        return new Decision(dead, dead ? null : backoff(claims));
    }
}
