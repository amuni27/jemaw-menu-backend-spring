package com.agafari.com.publishgate;

import com.agafari.com.config.BillingFlags;
import com.agafari.com.config.BillingFlags.MarketFlags;
import com.agafari.com.config.FlagMatrixViolationException;
import com.agafari.com.service.PublishEligibility;
import com.agafari.com.service.RestaurantCutOver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF BE-4 (register §52 B-1): with the gate on in any market, the existing-restaurant cut-over must be configured;
 * otherwise every restaurant, the existing ones included, would be refused. Refused at startup like the flag matrix.
 */
class PublishGateStartupTest {

    private static final MarketFlags OFF = new MarketFlags(false, false, false, false);
    private static final MarketFlags GATED = new MarketFlags(false, false, true, false);

    private static PublishEligibility build(MarketFlags us, MarketFlags et, String cutOver) {
        return new PublishEligibility(new BillingFlags(false, false, false, us, et), new RestaurantCutOver(cutOver), null, null, null, null, null, null);
    }

    @Test
    void theGateOnWithoutTheCutOverIsRefused() {
        for (MarketFlags[] m : new MarketFlags[][]{{GATED, OFF}, {OFF, GATED}, {GATED, GATED}}) {
            assertThatThrownBy(() -> build(m[0], m[1], ""))
                    .isInstanceOf(FlagMatrixViolationException.class)
                    .hasMessageContaining("publish-requires-plan requires app.monetization.plan-selection-required-from");
        }
    }

    @Test
    void theGateOnWithTheCutOverOrTheGateOffStarts() {
        assertThatCode(() -> build(GATED, GATED, "2026-12-01T00:00:00Z")).doesNotThrowAnyException();
        assertThatCode(() -> build(OFF, OFF, "")).doesNotThrowAnyException();
        assertThat(build(OFF, OFF, "").gateOn(com.agafari.com.billing.Market.ET)).isFalse();
        assertThat(build(OFF, GATED, "2026-12-01T00:00:00Z").gateOn(com.agafari.com.billing.Market.ET)).isTrue();
    }
}
