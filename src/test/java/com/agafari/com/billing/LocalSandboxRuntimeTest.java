package com.agafari.com.billing;

import com.agafari.com.service.MonetizationClock;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF B-L1 (register §93): an active local sandbox, against the real checkout and payment-outcome services. It only ever allows
 * the US market, and only until its acknowledged time: afterwards checkouts are refused (404, as with billing off) and no
 * payment outcome is applied, with no restart. The sandbox here is built from valid synthetic inputs through the same
 * constructor production uses; production code has no way to switch it on from a test.
 */
@Import(LocalSandboxRuntimeTest.ActiveSandbox.class)
class LocalSandboxRuntimeTest extends CheckoutTestBase {

    static final Instant UNTIL = Instant.now().plus(Duration.ofDays(2));

    @TestConfiguration
    static class ActiveSandbox {
        @Bean
        @Primary
        LocalSandboxActivation activeLocalSandbox(MonetizationClock clock) {
            return new LocalSandboxActivation(new LocalSandboxActivation.Inputs(true, true, UNTIL.toString(), true, List.of("local-sandbox"), true,
                    LocalSandboxActivation.KeyKind.TEST, "acct_" + "SyntheticBL1",
                    Map.of("app.billing.stripe.success-url", "http://127.0.0.1:5212/billing/return", "app.billing.stripe.cancel-url", "http://127.0.0.1:5212/billing/cancelled",
                            "app.billing.stripe.trusted-origins", "http://127.0.0.1:5212", "app.public-menu-base-url", "http://127.0.0.1:5212"),
                    "jdbc:postgresql://127.0.0.1:54409/agafari_sandbox_runtime", Set.of(), false), clock);
        }
    }

    @Autowired private PaymentOutcomeService outcomes;
    @Autowired private LocalSandboxActivation sandbox;

    @AfterEach
    void reset() {
        outcomes.setBillingEnabled(Market.US, false);
        outcomes.setBillingEnabled(Market.ET, false);
    }

    private Tenant usOwner(String label) {
        Tenant t = data.tenant(label);
        jdbc.update("update business set country = 'US' where id = ?", t.businessId());
        return t;
    }

    @Test
    void theSandboxInUseHereIsActive() {
        assertThat(sandbox.active()).isTrue();
        assertThat(ProviderActivationReadiness.ready()).as("the gates stay open").isFalse();
    }

    @Test
    void ethiopianBillingIsRefusedWhileTheSandboxIsActive() throws Exception {
        assertThat(httpStatus(owner, "bl1-et-1", price)).as("ET checkout: as with billing off").isEqualTo(404);
        assertThat(checkouts.checkoutAvailable(owner.businessId())).isFalse();
        outcomes.setBillingEnabled(Market.ET, true);
        assertThat(outcomes.billingActive(Market.ET)).as("no ET payment outcome").isFalse();
        assertThat(count()).isZero();
    }

    @Test
    void usBillingWorksUntilTheAcknowledgedTimeAndStopsAfterItWithoutARestart() throws Exception {
        Tenant us = usOwner("bl1-us");
        String usPrice = price("MODEL_4", "US", "USD", 2500);
        outcomes.setBillingEnabled(Market.US, true);
        assertThat(checkouts.checkoutAvailable(us.businessId())).isTrue();
        assertThat(outcomes.billingActive(Market.US)).isTrue();
        assertThat(httpStatus(us, "bl1-us-1", usPrice)).as("before expiry").isNotEqualTo(404);

        clock.setOffset(Duration.between(Instant.now(), UNTIL));
        assertThat(checkouts.checkoutAvailable(us.businessId())).as("expired").isFalse();
        assertThat(outcomes.billingActive(Market.US)).isFalse();
        assertThat(httpStatus(us, "bl1-us-2", usPrice)).as("after expiry: as with billing off").isEqualTo(404);
    }

    private int count() {
        return jdbc.queryForObject("select count(*) from checkout_session where business_id = ?", Integer.class, owner.businessId());
    }
}
