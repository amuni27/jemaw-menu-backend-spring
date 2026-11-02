package com.agafari.com.dto.response;

import com.agafari.com.jpa.entity.TrialIntent;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * AGF E4-M5 recovery: a restaurant's trial intent for the Super Admin. {@code needsAttention}: still pending after
 * {@link #NEEDS_ATTENTION_AFTER} failed attempts (it keeps being retried daily). {@code lastError} is an error category,
 * never a message.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrialIntentSummary(String businessId, String businessName, String status, String resolution, int configVersion,
                                 int failedAttempts, Instant lastAttemptAt, String lastError, Instant createdAt, Instant resolvedAt,
                                 boolean needsAttention) {

    public static final int NEEDS_ATTENTION_AFTER = 3;

    public static TrialIntentSummary of(TrialIntent i, String businessName) {
        return new TrialIntentSummary(i.getBusinessId(), businessName, i.getStatus(), i.getResolution(), i.getConfigVersion(),
                i.getFailedAttempts(), i.getLastAttemptAt(), i.getLastError(), i.getCreatedAt(), i.getResolvedAt(),
                i.isPending() && i.getFailedAttempts() >= NEEDS_ATTENTION_AFTER);
    }
}
