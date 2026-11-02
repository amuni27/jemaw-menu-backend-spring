package com.agafari.com.dto.response;

import java.time.LocalDate;
import java.util.List;

/** AGF E4-M7: an ad's served impressions (total, per restaurant-local day for the last 30 days, per restaurant). */
public record AdImpressionsResponse(String adId, long total, List<Day> days, List<Restaurant> restaurants) {

    public record Day(LocalDate date, long served) {
    }

    public record Restaurant(String businessId, String businessName, long served) {
    }
}
