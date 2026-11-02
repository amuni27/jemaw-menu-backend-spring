package com.agafari.com.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * AGF E4-M7: an Agafari ad for the Super Admin. The schedule is the stored UTC instants plus the same moments as local date
 * and time with their offset in {@code scheduleTimeZone} (D-M7-1). {@code servedImpressions}: how often it was delivered
 * with a guest menu (server-side delivery, not confirmed on-screen visibility).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdResponse(String id, String title, String body, String titleAm, String bodyAm, String linkUrl, String imageUrl, String imageAlt,
                         Instant startsAt, Instant endsAt, String scheduleTimeZone, String startLocal, String startOffset, String endLocal,
                         String endOffset, String status, long servedImpressions, Instant createdAt, Instant updatedAt, int rowVersion) {
}
