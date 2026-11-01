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
                            PlanRequestSummary pendingRequest, Boolean canRequest, Boolean trialAvailable, Boolean featuresUnaffected) {

    public static OwnerPlanView hidden() {
        return new OwnerPlanView(false, null, null, null, null, null, null, null, null);
    }

    public record Plan(String code, String model, String adSource, boolean paid) {
    }

    /** The current subscription as the owner sees it: no admin identity, no configuration version. */
    public record Subscription(String planCode, String status, Instant periodEnd, String sourceLabel, boolean outsideCurrentConfiguration) {
    }
}
