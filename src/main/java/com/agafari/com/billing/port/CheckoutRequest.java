package com.agafari.com.billing.port;

/** AGF SUB-R4-04: the owner's request; the client only names a price (TA §6). */
public record CheckoutRequest(String businessId, String priceId) {
}
