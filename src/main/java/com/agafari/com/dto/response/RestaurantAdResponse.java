package com.agafari.com.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * AGF E4-M8: a restaurant ad. The schedule is the stored UTC instants plus the same moments as local date and time with their
 * offset in {@code scheduleTimeZone}. {@code businessName} only in the Super Admin list. No impression or click data (Q-M8-8).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RestaurantAdResponse(String id, String businessId, String businessName, String title, String body, String titleAm, String bodyAm,
                                   String linkUrl, String imageUrl, String imageAlt, Instant startsAt, Instant endsAt, String scheduleTimeZone,
                                   String startLocal, String startOffset, String endLocal, String endOffset, String status, String suspensionReason,
                                   Instant suspendedAt, Instant createdAt, Instant updatedAt, int rowVersion) {
}
