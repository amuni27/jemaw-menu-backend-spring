package com.agafari.com.dto.response;

import java.time.Instant;

/**
 * AGF E4-M3: one subscription period. Manual records always say {@code paymentVerified = false} with the label
 * "Manual record, no payment verified". AGF E4-M5: trials say "Trial (automatic), no payment involved";
 * {@code effectiveStatus}/{@code effectiveEnd} report a trial as ended once its period end has passed, even before the job.
 * AGF E4-M6: {@code grandfathering} (absent unless grandfathered); with transitions enabled, a grandfathered subscription
 * past its approved end is reported ended the same way.
 */
public record SubscriptionResponse(String id, String businessId, String planCode, String status, int configVersion,
                                   Instant periodEnd, Instant transitionDate, String source, boolean paymentVerified,
                                   String sourceLabel, Instant createdAt, String createdBy, Instant updatedAt, Instant endedAt,
                                   int rowVersion, boolean outsideCurrentConfiguration, String effectiveStatus, Instant effectiveEnd,
                                   GrandfatheringInfo grandfathering) {
}
