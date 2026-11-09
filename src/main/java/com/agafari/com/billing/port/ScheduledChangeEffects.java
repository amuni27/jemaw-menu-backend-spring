package com.agafari.com.billing.port;

/** AGF SUB-R4-04: whether a scheduled change also needs a provider-side schedule (US, DD §4.4). */
public record ScheduledChangeEffects(boolean providerScheduleRequired) {
}
