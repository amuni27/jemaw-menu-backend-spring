package com.agafari.com.billing.stripe;

import com.agafari.com.billing.UsBillingStrategy;
import com.agafari.com.billing.port.BillingStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.util.Map;

/**
 * AGF SUB-R5-02 (tests only): the real Stripe adapter and strategy as the US market's strategy, with the SDK's HTTP layer
 * replaced by {@link StripeStub} and <b>synthetic</b> test-mode credentials assembled at run time. No network call is possible.
 */
@TestConfiguration
public class StripeStubBillingConfig {

    public static final String SECRET_KEY = "sk_" + "test_" + "SyntheticR502Flow000000000001";
    public static final String WEBHOOK_SECRET = "whsec_" + "SyntheticR502Flow000000000002";
    public static final String WEBHOOK_SECRET_PREVIOUS = "whsec_" + "SyntheticR502Flow000000000003";
    public static final String ORIGIN = "http://localhost:5173";

    @Bean
    public StripeStub stripeStub() {
        return new StripeStub();
    }

    @Bean
    public StripeProviderClient stripeClient(StripeStub stub) {
        StripeCredentials credentials = StripeCredentials.fromEnvironment(Map.of(StripeCredentials.ENV_SECRET_KEY, SECRET_KEY, StripeCredentials.ENV_WEBHOOK_SECRET, WEBHOOK_SECRET,
                StripeCredentials.ENV_WEBHOOK_SECRET_PREVIOUS, WEBHOOK_SECRET_PREVIOUS)::get);
        StripeSettings settings = new StripeSettings(ORIGIN + "/billing/return?session={CHECKOUT_SESSION_ID}", ORIGIN + "/billing/cancelled", ORIGIN);
        return new StripeProviderClient(credentials, settings, stub, Clock.systemUTC());
    }

    @Bean
    public BillingStrategy stripeUsStrategy(StripeProviderClient client) {
        return new UsBillingStrategy(new StripeBillingStrategy(client));
    }
}
