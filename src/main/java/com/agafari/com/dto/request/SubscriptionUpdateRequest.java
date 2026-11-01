package com.agafari.com.dto.request;

import java.time.Instant;

/**
 * AGF E4-M3: end the current subscription ({@code status = EXPIRED}) or adjust its period end. {@code planCode} exists
 * only to refuse it explicitly: plans never change in M3 (D-M3-10).
 */
public record SubscriptionUpdateRequest(String status, Instant periodEnd, String planCode, Integer rowVersion) {
}
