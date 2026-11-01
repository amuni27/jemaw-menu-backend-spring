package com.agafari.com.dto.response;

import com.agafari.com.service.Entitlements;
import com.fasterxml.jackson.annotation.JsonInclude;


/**
 * AGF E4-M3: a restaurant's current subscription (absent when none) and its computed entitlements. AGF E4-M4: and its
 * pending plan request, if any.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BusinessSubscriptionView(String businessId, String businessName, SubscriptionResponse current,
                                       Entitlements entitlements, boolean monetizationActive, PlanRequestSummary pendingRequest) {
}
