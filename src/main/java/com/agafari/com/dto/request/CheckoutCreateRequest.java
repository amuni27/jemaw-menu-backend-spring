package com.agafari.com.dto.request;

/**
 * AGF SUB-R4-05: the client only names a price; the amount, currency and restaurant are the server's.
 * AGF SUB-R4-06: replacing an active checkout for another price needs that checkout's id and row version, so a stale
 * confirmation can't replace a checkout the owner never saw.
 */
public record CheckoutCreateRequest(String priceId, String replaceCheckoutId, Integer replaceRowVersion) {
}
