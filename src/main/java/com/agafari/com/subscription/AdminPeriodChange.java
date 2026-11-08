package com.agafari.com.subscription;

import java.time.Instant;

/**
 * AGF SUB-R2-03: one Super Admin change to a current subscription, applied and saved once.
 *
 * @param periodEnd           the new end, or null to leave it as it is
 * @param clearTransitionDate clears the transition date (a period end set on an open-ended row replaces it, E4-M6 Q12)
 * @param end                 ends the subscription in the same change
 * @param now                 the time of the change
 */
public record AdminPeriodChange(Instant periodEnd, boolean clearTransitionDate, boolean end, Instant now) {
}
