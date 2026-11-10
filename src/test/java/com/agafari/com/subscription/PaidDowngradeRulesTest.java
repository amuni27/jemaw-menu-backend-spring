package com.agafari.com.subscription;

import com.agafari.com.subscription.PaidDowngradeRules.PriceFacts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF SUB-R4-15 (PO D1): which paid → paid request is a scheduled downgrade. Plan codes other than the real undecided pair
 * are hypothetical fixtures: today's product has no D1-eligible pair.
 */
class PaidDowngradeRulesTest {

    private static PriceFacts p(String plan, String market, String currency, String interval, long amount) {
        return new PriceFacts(plan, market, currency, interval, amount);
    }

    private static final PriceFacts CURRENT = p("PLAN_A", "ET", "ETB", "MONTH", 2500);

    @Test
    void aStrictlyLowerPriceInTheSameMarketCurrencyAndIntervalIsADowngrade() {
        assertThat(PaidDowngradeRules.refusal(CURRENT, p("PLAN_B", "ET", "ETB", "MONTH", 1500))).isEmpty();
    }

    @Test
    void anEqualPriceIsLateralAndRefused() {
        assertThat(PaidDowngradeRules.refusal(CURRENT, p("PLAN_B", "ET", "ETB", "MONTH", 2500))).hasValueSatisfying(v -> assertThat(v).contains("same price"));
    }

    @Test
    void aHigherPriceIsAnUpgradeAndRefused() {
        assertThat(PaidDowngradeRules.refusal(CURRENT, p("PLAN_B", "ET", "ETB", "MONTH", 2600))).hasValueSatisfying(v -> assertThat(v).contains("upgrade"));
    }

    @Test
    void anIntervalChangeIsRefusedEvenWhenCheaper() {
        assertThat(PaidDowngradeRules.refusal(CURRENT, p("PLAN_B", "ET", "ETB", "YEAR", 100))).hasValueSatisfying(v -> assertThat(v).contains("interval"));
    }

    @Test
    void anotherMarketOrCurrencyIsRefused() {
        assertThat(PaidDowngradeRules.refusal(CURRENT, p("PLAN_B", "US", "USD", "MONTH", 100))).isPresent();
        assertThat(PaidDowngradeRules.refusal(CURRENT, p("PLAN_B", "ET", "USD", "MONTH", 100))).isPresent();
    }

    @Test
    void model3AndModel4AreUndecidedInBothDirectionsWhateverTheirPrices() {
        assertThat(PaidDowngradeRules.refusal(p("MODEL_4", "ET", "ETB", "MONTH", 2500), p("MODEL_3", "ET", "ETB", "MONTH", 1500)))
                .hasValueSatisfying(v -> assertThat(v).contains("aren't available yet"));
        assertThat(PaidDowngradeRules.refusal(p("MODEL_3", "ET", "ETB", "MONTH", 2500), p("MODEL_4", "ET", "ETB", "MONTH", 1500)))
                .hasValueSatisfying(v -> assertThat(v).contains("aren't available yet"));
    }

    @Test
    void theSamePlanOrAMissingPriceIsRefused() {
        assertThat(PaidDowngradeRules.refusal(CURRENT, p("PLAN_A", "ET", "ETB", "MONTH", 1000))).isPresent();
        assertThat(PaidDowngradeRules.refusal(CURRENT, null)).isPresent();
        assertThat(PaidDowngradeRules.refusal(null, CURRENT)).isPresent();
    }
}
