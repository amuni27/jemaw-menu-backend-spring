package com.agafari.com.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * AGF BE-5a / BE-4 (register §49 Q3, §52 B-1): the one definition of a "new" restaurant. Restaurants created at or after
 * {@code app.monetization.plan-selection-required-from} (an ISO-8601 instant) are new: they must choose a plan (BE-5a) and,
 * while the publish gate is on, need one to publish (BE-4). Earlier ones (P-6) are existing and exempt. Empty (the default):
 * not configured. An invalid value stops startup.
 */
@Component
public class RestaurantCutOver {

    private volatile Instant from;

    public RestaurantCutOver(@Value("${app.monetization.plan-selection-required-from:}") String from) {
        this.from = parse(from);
    }

    public static Instant parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalStateException("app.monetization.plan-selection-required-from must be an ISO-8601 instant such as 2026-12-01T00:00:00Z", e);
        }
    }

    /** The cut-over, or null while it isn't configured. */
    public Instant from() {
        return from;
    }

    /** Whether a restaurant created at {@code createdAt} is new; false while the cut-over isn't configured or the time is unknown. */
    public boolean isNew(Instant createdAt) {
        Instant cut = from;
        return cut != null && createdAt != null && !createdAt.isBefore(cut);
    }

    /** Tests only: the cut-over itself comes from configuration. */
    public void setFrom(Instant from) {
        this.from = from;
    }
}
