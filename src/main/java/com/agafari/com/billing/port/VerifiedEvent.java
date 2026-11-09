package com.agafari.com.billing.port;

import java.time.Instant;

/** AGF SUB-R4-04: an inbox event after signature verification. */
public record VerifiedEvent(String provider, String eventId, String type, String providerPaymentRef, Instant occurredAt) {
}
