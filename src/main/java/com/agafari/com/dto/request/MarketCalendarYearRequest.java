package com.agafari.com.dto.request;

import java.util.List;

/** AGF SUB-R4-12 (R-6, I4): whether a market's year is published, and its weekend days (Monday … Sunday). */
public record MarketCalendarYearRequest(Boolean published, List<String> weekendDays) {
}
