package com.agafari.com.dto.response;

import java.time.Instant;

/** AGF SUB-R4-05: a checkout as its owner sees it; {@code redirectUrl} is present only while OPEN. {@code rowVersion} confirms a replacement (SUB-R4-06). */
public record CheckoutResponse(String checkoutId, String status, String redirectUrl, Instant expiresAt, int rowVersion) {
}
