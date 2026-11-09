package com.agafari.com.billing;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;

/**
 * AGF SUB-R4-08 (PD-2a): was this plan enabled by the configuration in force at the provider's successful-payment time?
 * A version is in force from its {@code effectiveFrom}, so a payment at exactly that instant follows the new version.
 */
public final class EligibilityAtTime {

    private EligibilityAtTime() {
    }

    public static boolean eligible(Collection<ConfigVersionFacts> history, String planCode, Instant tPay) {
        Objects.requireNonNull(tPay, "tPay");
        return history.stream().filter(v -> !v.effectiveFrom().isAfter(tPay))
                .max(Comparator.comparing(ConfigVersionFacts::effectiveFrom).thenComparing(ConfigVersionFacts::version))
                .map(v -> v.enabledPlanCodes().contains(planCode)).orElse(false);
    }
}
