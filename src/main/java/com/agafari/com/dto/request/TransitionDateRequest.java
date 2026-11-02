package com.agafari.com.dto.request;

/**
 * AGF E4-M6: sets (or, without {@code transitionDate}, clears through the clear endpoint) the transition date of an
 * open-ended grandfathered subscription. Text on purpose: only a plain {@code YYYY-MM-DD} date is accepted, meaning the end
 * of that day in the restaurant's time zone.
 */
public record TransitionDateRequest(String transitionDate, Integer rowVersion) {
}
