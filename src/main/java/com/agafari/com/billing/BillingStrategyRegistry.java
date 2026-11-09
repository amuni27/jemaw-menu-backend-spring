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

    public BillingStrategy forBusiness(String businessId) {
        List<String> markets = jdbc.queryForList("select distinct market from billing_account where business_id = ?", String.class, businessId);
        if (markets.isEmpty()) throw new IllegalStateException("The restaurant has no billing account");
        if (markets.size() > 1) throw new IllegalStateException("The restaurant has billing accounts in more than one market");
        return forMarket(Market.fromCountry(markets.get(0)).orElseThrow(() -> new IllegalStateException("Unknown market " + markets.get(0))));
    }
}
