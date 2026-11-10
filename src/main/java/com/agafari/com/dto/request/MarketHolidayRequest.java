package com.agafari.com.dto.request;

/** AGF SUB-R4-12 (R-6): one officially announced public holiday; {@code date} is ISO (yyyy-MM-dd). */
public record MarketHolidayRequest(String date, String name, String sourceNote) {
}
