package com.agafari.com.dto.request;

import java.time.Instant;

/** AGF E4-M5: convert the current trial into a manual Model 1 record (no payment verified). */
public record TrialConvertRequest(Integer expectedConfigVersion, Integer rowVersion, Instant periodEnd) {
}
