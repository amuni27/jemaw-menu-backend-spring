package com.agafari.com.billing.stripe;

import com.agafari.com.billing.UsBillingStrategy;
import com.agafari.com.billing.port.BillingStrategy;
import com.agafari.com.service.MonetizationClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * AGF R5-02 Q6 (tests only): the real Stripe adapter and strategy as the US market's strategy for the
 * {@link AuthenticStripeFixtures AUTHENTIC}-fixture flow tests. The SDK's HTTP layer is {@link StripeStub} (no network), the
 * credentials are synthetic, and the adapter's clock follows {@link MonetizationClock}, so a test can run "at" the captured
 * payment time and the authentic timestamps keep their real relationships (and the tests don't expire with the real period).
 * Kept separate from {@link StripeStubBillingConfig} so no accepted test file changes.
 */
@TestConfiguration
public class AuthenticStripeBillingConfig {

    public static final String SECRET_KEY = "sk_" + "test_" + "SyntheticQ6Flow0000000000001";
    public static final String WEBHOOK_SECRET = "whsec_" + "SyntheticQ6Flow0000000000002";
    public static final String ORIGIN = "http://localhost:5173";

    @Bean
    public StripeStub stripeStub() {
        return new StripeStub();
    }

    static Clock following(MonetizationClock monetization) {
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return monetization.now();
            }
        };
    }

    @Bean
    public StripeProviderClient stripeClient(StripeStub stub, MonetizationClock clock) {
        StripeCredentials credentials = StripeCredentials.fromEnvironment(Map.of(StripeCredentials.ENV_SECRET_KEY, SECRET_KEY,
                StripeCredentials.ENV_WEBHOOK_SECRET, WEBHOOK_SECRET)::get);
        StripeSettings settings = new StripeSettings(ORIGIN + "/billing/return?session={CHECKOUT_SESSION_ID}", ORIGIN + "/billing/cancelled", ORIGIN);
        return new StripeProviderClient(credentials, settings, stub, following(clock));
    }

    @Bean
    public BillingStrategy stripeUsStrategy(StripeProviderClient client) {
        return new UsBillingStrategy(new StripeBillingStrategy(client));
    }
}
