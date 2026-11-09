package com.agafari.com.billing;

import java.time.Instant;
import java.util.Set;

/** AGF SUB-R4-08: one version of the monetization configuration, as eligibility needs it. */
public record ConfigVersionFacts(int version, Instant effectiveFrom, Set<String> enabledPlanCodes) {

    public ConfigVersionFacts {
        enabledPlanCodes = Set.copyOf(enabledPlanCodes);
    }
}
