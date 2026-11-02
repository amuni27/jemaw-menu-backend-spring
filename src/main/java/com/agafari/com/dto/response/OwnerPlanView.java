package com.agafari.com.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * AGF E4-M4: what an owner sees about plans. Only {@code visible=false} while the owner visibility flag is off or
 * monetization isn't configured. No prices, payment or trial data exist; {@code featuresUnaffected} is always true (no
 * enforcement). {@code configVersion} is sent back with a request; the UI doesn't show it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OwnerPlanView(boolean visible, String setting, Integer configVersion, List<Plan> plans, Subscription current,
                            PlanRequestSummary pendingRequest, Boolean canRequest, Boolean trialAvailable, Boolean featuresUnaffected,
                            Trial trial, EndedPlan endedPlan) {

    public static OwnerPlanView hidden() {
        return new OwnerPlanView(false, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * AGF E4-M6: the owner's view of grandfathering: the approved end (absent when none), whole days left, and whether
     * transitions are on (only then does the plan actually end). Dates are shown in the restaurant's time zone.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Grandfathering(Instant until, Long daysLeft, boolean transitionsEnabled, String timeZone) {
    }

    /** AGF E4-M6: the plan the restaurant's subscription ended on through grandfathering. */
    public record EndedPlan(String planCode, Instant endedAt, String timeZone) {
    }

    /** AGF E4-M5: the restaurant's trial in its effective state only (the owner never sees the persisted status). */
    /** AGF E4-M5: {@code timeZone} is the restaurant's, in which the end date is shown (Option A). */
    public record Trial(Instant startedAt, Instant endsAt, long daysLeft, String effectiveStatus, String timeZone) {
    }

    public record Plan(String code, String model, String adSource, boolean paid) {
    }

    /** The current subscription as the owner sees it: no admin identity, no configuration version. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Subscription(String planCode, String status, Instant periodEnd, String sourceLabel, boolean outsideCurrentConfiguration,
                               Grandfathering grandfathering) {
    }
}
