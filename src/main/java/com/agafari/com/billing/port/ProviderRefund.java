package com.agafari.com.billing.port;

/** AGF SUB-R4-04: a refund found at the provider. Finding one confirms presence; not finding one proves nothing (BD-22). */
public record ProviderRefund(String providerRefundRef) {
}
