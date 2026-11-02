package com.agafari.com.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/** AGF Self-Service Phase A: the started subscription; {@code created=false} for a repeated click (the same subscription). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubscriptionActivationResponse(String subscriptionId, String planCode, String status, boolean created, String withdrawnRequestId) {
}
