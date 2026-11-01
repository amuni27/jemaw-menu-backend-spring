package com.agafari.com.dto.response;

import java.time.Instant;

/** AGF E4-M4: a pending plan request as shown next to a restaurant's subscription (Super Admin and owner views). */
public record PlanRequestSummary(String id, String planCode, int configVersion, Instant requestedAt, int rowVersion,
                                 boolean outsideCurrentConfiguration) {
}
