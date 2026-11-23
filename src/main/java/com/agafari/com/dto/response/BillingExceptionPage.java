package com.agafari.com.dto.response;

import java.time.Instant;
import java.util.List;

/** AGF EX-F (register §91): one page of exceptions for the Super Admin list; {@code businessId} is null for a platform exception. */
public record BillingExceptionPage(List<Item> items, int page, int size, long total) {

    public record Item(String id, String code, String status, String businessId, String businessName, Long amountMinor, String currency,
                       boolean involvesCustomerFunds, Instant createdAt, Instant dueAt, boolean overdue, String claimedBy, String escalatedTo,
                       String providerEventId, String sourceJobId) {
    }
}
