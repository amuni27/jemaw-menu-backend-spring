package com.agafari.com.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * AGF E4-M6: a grandfathered subscription (its plan isn't enabled by the current configuration). {@code until} is the
 * approved end: the period end, or for an open-ended subscription its valid transition date, or none. {@code untilSource}
 * is {@code PERIOD_END}, {@code TRANSITION_DATE} or {@code NONE}. {@code transitionDateIgnored}: a stored transition date
 * doesn't apply (set in an earlier grandfathering period, or without a matching record). With {@code transitionsEnabled}
 * false this is information only; {@code overdue} then says the end has passed without effect.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GrandfatheringInfo(Instant until, String untilSource, boolean transitionDateIgnored, boolean transitionsEnabled, boolean overdue) {
}
