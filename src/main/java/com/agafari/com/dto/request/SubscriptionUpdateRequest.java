package com.agafari.com.dto.request;

import java.time.Instant;

/**
 * AGF E4-M3: end the current subscription ({@code status = EXPIRED}) or adjust its period end. {@code planCode} exists
 * only to refuse it explicitly: plans never change in M3 (D-M3-10). AGF E4-M5: {@code periodEndDate} extends a running
 * trial to the end of that calendar day in the restaurant's time zone (never together with {@code periodEnd} or an end).
 * It is text on purpose: only a plain {@code YYYY-MM-DD} date is accepted (parsed strictly by the service), so a
 * date-time can't be silently truncated to a date.
 */
public record SubscriptionUpdateRequest(String status, Instant periodEnd, String planCode, Integer rowVersion, String periodEndDate) {
}
