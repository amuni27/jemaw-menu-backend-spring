package com.agafari.com.jpa.entity;

import java.time.Instant;

/** AGF E4-M8: an ad's editable content and schedule, already validated ({@code AdContentRules}, {@code AdSchedule}). */
public record AdContent(String title, String body, String titleAm, String bodyAm, String linkUrl, Instant startsAt, Instant endsAt, String scheduleTimeZone) {
}
