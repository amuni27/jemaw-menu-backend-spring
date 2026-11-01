package com.agafari.com.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** AGF E4-M4: a plan request. {@code requestedBy} (masked) and {@code businessName} are filled for the Super Admin only. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanRequestResponse(String id, String businessId, String businessName, String planCode, int configVersion, String status,
                                  Instant requestedAt, String requestedBy, Instant resolvedAt, String subscriptionId, int rowVersion,
                                  boolean outsideCurrentConfiguration) {
}
