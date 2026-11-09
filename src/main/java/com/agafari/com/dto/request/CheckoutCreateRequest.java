package com.agafari.com.dto.request;

/** AGF SUB-R4-05: the client only names a price; the amount, currency and restaurant are the server's. */
public record CheckoutCreateRequest(String priceId) {
}
