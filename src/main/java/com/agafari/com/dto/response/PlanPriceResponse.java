package com.agafari.com.dto.response;

import java.time.Instant;

/** AGF SUB-R3-01. */
public record PlanPriceResponse(String id, String planCode, String market, String currency, String interval, long amountMinor, int version,
                                String providerPriceRef, boolean active, Instant validFrom) {
}
