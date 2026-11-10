package com.agafari.com.subscription;

import java.util.Optional;
import java.util.Set;

/**
 * AGF SUB-R4-15 (PO D1): whether an owner's request between two paid plans is a scheduled downgrade. Prices are compared only
 * within one market, currency and billing interval, and only a strictly lower price qualifies. Lateral changes, interval
 * changes and Model 3 ↔ Model 4 (CHG-03, CHG-04; undecided) are never downgrades here: a lower price alone doesn't decide them.
 * Pure.
 */
public final class PaidDowngradeRules {

    /** The unresolved product pair (CHG-03, CHG-04). */
    private static final Set<String> UNDECIDED_PAIR = Set.of("MODEL_3", "MODEL_4");

    public record PriceFacts(String planCode, String market, String currency, String interval, long amountMinor) {
    }

    private PaidDowngradeRules() {
    }

    /** Empty when {@code target} is a schedulable downgrade from {@code current}; otherwise why not. */
    public static Optional<String> refusal(PriceFacts current, PriceFacts target) {
        if (current == null) return Optional.of("The current subscription has no paid price");
        if (target == null) return Optional.of("That plan has no price in your market, currency and billing interval");
        if (!current.market().equals(target.market()) || !current.currency().equals(target.currency())) return Optional.of("That price is in another market or currency");
        if (!current.interval().equals(target.interval())) return Optional.of("Changing the billing interval isn't available");
        if (current.planCode().equals(target.planCode())) return Optional.of("This subscription is already on that plan");
        if (UNDECIDED_PAIR.contains(current.planCode()) && UNDECIDED_PAIR.contains(target.planCode())) {
            return Optional.of("Changes between these plans aren't available yet");
        }
        if (target.amountMinor() == current.amountMinor()) return Optional.of("A change to a plan at the same price isn't available yet");
        if (target.amountMinor() > current.amountMinor()) return Optional.of("That plan costs more: it is an upgrade, not a scheduled downgrade");
        return Optional.empty();
    }
}
