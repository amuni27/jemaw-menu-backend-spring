package com.agafari.com.billing;

import com.agafari.com.billing.port.BillingStrategy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * AGF SUB-R4-04 (BD-3, DD §6.2): the strategy of a market. A restaurant's market comes from its billing account, which equals
 * its country and never changes (V23.1). No strategy is a bean until a provider phase adds one, so nothing resolves in production yet.
 */
@Service
public class BillingStrategyRegistry {

    private final Map<Market, BillingStrategy> strategies = new EnumMap<>(Market.class);
    private final JdbcTemplate jdbc;

    public BillingStrategyRegistry(ObjectProvider<BillingStrategy> available, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        available.orderedStream().forEach(s -> {
            if (strategies.putIfAbsent(s.market(), s) != null) throw new IllegalStateException("Two billing strategies for market " + s.market());
        });
    }

    public BillingStrategy forMarket(Market market) {
        BillingStrategy strategy = market == null ? null : strategies.get(market);
        if (strategy == null) throw new IllegalStateException("No billing strategy for market " + market);
        return strategy;
    }

    /** AGF SUB-R4-07: the strategy whose provider has this name; never trusted beyond choosing whose signature to check. */
    public java.util.Optional<BillingStrategy> forProvider(String provider) {
        return strategies.values().stream().filter(s -> s.provider().equals(provider)).findFirst();
    }

    /**
     * AGF BILLING-UNSUPPORTED-EVENTS (register §96 U-3): the strategy a webhook route names, matching a <b>known</b> provider
     * code in any ASCII case ({@code stripe}, {@code Stripe}, {@code STRIPE}). Only printable ASCII is compared, so a Unicode
     * lookalike that folds to a code ({@code ſtripe}, {@code STRİPE}) is no provider. Nothing is created from the path; callers
     * store events under the strategy's own code.
     */
    public java.util.Optional<BillingStrategy> forWebhookRoute(String provider) {
        if (provider == null || provider.isEmpty() || !provider.chars().allMatch(c -> c > 0x20 && c < 0x7f)) return java.util.Optional.empty();
        return strategies.values().stream().filter(s -> s.provider().equalsIgnoreCase(provider)).findFirst();
    }

    public BillingStrategy forBusiness(String businessId) {
        List<String> markets = jdbc.queryForList("select distinct market from billing_account where business_id = ?", String.class, businessId);
        if (markets.isEmpty()) throw new IllegalStateException("The restaurant has no billing account");
        if (markets.size() > 1) throw new IllegalStateException("The restaurant has billing accounts in more than one market");
        return forMarket(Market.fromCountry(markets.get(0)).orElseThrow(() -> new IllegalStateException("Unknown market " + markets.get(0))));
    }
}
