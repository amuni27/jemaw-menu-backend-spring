package com.agafari.com.dto.response;

/** AGF E4-M3: a plan and whether the current monetization configuration enables it. */
public record SubscriptionPlanResponse(String code, String model, String setting, String adSource, boolean paid,
                                       boolean enabledByCurrentConfiguration) {
}
