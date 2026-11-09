package com.agafari.com.billing.port;

import java.time.Instant;

/** AGF SUB-R4-04: the fields of a scheduled change a strategy needs. */
public record ScheduledChangeView(String id, String kind, String targetPlanCode, Instant effectiveAt) {
}
