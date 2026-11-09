package com.agafari.com.billing.port;

/** AGF SUB-R4-04: the provider accepted a refund and returned its own reference (a refund identity, PO D4). */
public record RefundSubmission(String providerRefundRef) {
}
