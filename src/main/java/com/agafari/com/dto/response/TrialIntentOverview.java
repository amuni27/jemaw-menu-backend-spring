package com.agafari.com.dto.response;

import java.util.List;

/** AGF E4-M5 recovery: trials pending recovery (counts, and the oldest pending intents first). */
public record TrialIntentOverview(long pending, long needsAttention, List<TrialIntentSummary> items) {
}
