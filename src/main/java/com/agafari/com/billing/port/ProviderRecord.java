package com.agafari.com.billing.port;

import java.time.Instant;

/** AGF SUB-R4-04: one entry of a provider listing. */
public record ProviderRecord(String providerRef, RecordType type, Instant occurredAt) {
}
