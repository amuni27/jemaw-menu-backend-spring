package com.agafari.com.config;

import com.agafari.com.config.BillingFlags.MarketFlags;

import java.util.ArrayList;
import java.util.List;

/** AGF SUB-R2-12 (Migration §6, BD-10, R-3): which flag combinations may run. Pure; every violation is reported at once. */
public final class FlagMatrix {

    private FlagMatrix() {
    }

    public static List<String> violations(BillingFlags flags, boolean jobsEnabled, FlagFacts facts) {
        List<String> found = new ArrayList<>();
        if (jobsEnabled && !facts.postgres()) found.add("app.jobs.enabled requires PostgreSQL");
        if (flags.periodEndEnabled()) {
            if (!flags.periodEndReportAcknowledged()) found.add("period-end-enabled requires the period-end report to be acknowledged");
            if (!facts.postgres()) found.add("period-end-enabled requires PostgreSQL");
        }
        market("US", flags.us(), flags, facts, found);
        market("ET", flags.et(), flags, facts, found);
        return found;
    }

    private static void market(String name, MarketFlags m, BillingFlags flags, FlagFacts facts, List<String> found) {
        if (m.pricesVisible() && !facts.pricesAvailable()) found.add("prices-visible (" + name + ") requires an active price for every enabled paid model");
        if (m.billingEnabled()) {
            String flag = "billing-enabled (" + name + ") requires ";
            if (!m.pricesVisible()) found.add(flag + "prices-visible");
            if (!flags.periodEndEnabled()) found.add(flag + "period-end-enabled");
            if (!facts.providerSecretsPresent()) found.add(flag + "provider secrets");
            if (!flags.pd9Decided()) found.add(flag + "PD-9 to be recorded as decided");
            if (!facts.postgres()) found.add(flag + "PostgreSQL");
        }
        if (m.publishRequiresPlan()) {
            String flag = "publish-requires-plan (" + name + ") requires ";
            if (!flags.periodEndEnabled()) found.add(flag + "period-end-enabled");
            if (!facts.dryRunSignedOff()) found.add(flag + "a signed dry run");
            if (!facts.postgres()) found.add(flag + "PostgreSQL");
        }
        if (m.guestEnforcement()) {
            String flag = "guest-enforcement (" + name + ") requires ";
            if (!m.publishRequiresPlan()) found.add(flag + "publish-requires-plan");
            if (!facts.legacyRunCompleted()) found.add(flag + "a completed legacy run");
            if (!facts.unexplainedPublishedCleared()) found.add(flag + "the unexplained published report to be empty or signed off");
            if (!facts.postgres()) found.add(flag + "PostgreSQL");
        }
    }
}
