package com.agafari.com.dto.response;

import java.time.Instant;

/** AGF SUB-R2-11: an owner's scheduled change. Intent only: it grants nothing until {@code effectiveAt}. */
public record ScheduledChangeResponse(String id, String kind, String targetPlanCode, Instant effectiveAt, String status) {
}
