package com.agafari.com.subscription;

import java.time.Instant;

/**
 * AGF SUB-R2-01: the read-time state of a subscription row.
 *
 * @param status         the effective status (may differ from the stored one: an ended trial, a passed MANUAL end, grace)
 * @param periodEnd      the row's period end (null when open-ended)
 * @param graceUntil     end of grace for a PROVIDER row with a period end, else null
 * @param entitledToPlan true while the row still grants its plan (TRIAL, ACTIVE, GRANDFATHERED, PAST_DUE within grace)
 * @param reasonCode     why the effective status was chosen (stable codes, for logs and tests)
 */
public record EffectiveView(LifecycleStatus status, Instant periodEnd, Instant graceUntil, boolean entitledToPlan,
                            String reasonCode) {
}
