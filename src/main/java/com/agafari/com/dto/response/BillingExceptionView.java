package com.agafari.com.dto.response;

import java.time.Instant;

/** AGF SUB-R4-11: one financial exception as the Super Admin sees it. {@code rowVersion} is sent back with every action. */
public record BillingExceptionView(String id, String businessId, String code, String status, String paymentId, String checkoutId, String providerEventId,
                                   String refundRequestId, Long amountMinor, String currency, Instant paidAt, boolean involvesCustomerFunds, String ownerUserId,
                                   Instant claimedAt, Instant escalatedAt, String escalationReason, String escalatedTo, String resolution, String resolutionNote,
                                   String resolvedBy, Instant resolvedAt, Instant createdAt, int rowVersion) {
}
