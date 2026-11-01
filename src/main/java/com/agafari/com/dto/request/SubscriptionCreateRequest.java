package com.agafari.com.dto.request;

import java.time.Instant;

/** AGF E4-M3: a manual subscription record. Status (ACTIVE) and source (MANUAL) are set by the server, never here. */
public record SubscriptionCreateRequest(String planCode, Instant periodEnd, Integer expectedConfigVersion) {
}
