package com.agafari.com.dto.request;

import java.math.BigDecimal;
import java.time.Instant;

/** AGF SUB-R3-01: a new price version. {@code amountMinor} is a decimal type only so that a fractional value can be refused. */
public record PlanPriceCreateRequest(String planCode, String market, String interval, BigDecimal amountMinor, Instant validFrom, String providerPriceRef) {
}
