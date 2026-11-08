package com.agafari.com.dto.response;

import java.time.Instant;
import java.util.List;

/** AGF SUB-R2-10: current MANUAL subscriptions with a past or missing period end, for review before period-end enforcement. */
public record PeriodEndReportPage(List<Item> items, int page, int size, long total) {

    public record Item(String subscriptionId, String businessId, String businessName, String planCode, String status, Instant periodEnd,
                       String reason, boolean openEnded, int rowVersion) {
    }
}
