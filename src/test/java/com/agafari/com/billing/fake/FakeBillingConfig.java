package com.agafari.com.billing.fake;

import com.agafari.com.billing.EtBillingStrategy;
import com.agafari.com.billing.Market;
import com.agafari.com.billing.UsBillingStrategy;
import com.agafari.com.billing.port.BillingStrategy;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/** AGF SUB-R4-05: one fake provider per market, through the real market shells. Test classpath only. */
@TestConfiguration
public class FakeBillingConfig {

    @Bean
    public FakeProviderClient fakeEt() {
        return new FakeProviderClient();
    }

    @Bean
    public FakeProviderClient fakeUs() {
        return new FakeProviderClient();
    }

    @Bean
    public BillingStrategy etStrategy(FakeProviderClient fakeEt) {
        return new EtBillingStrategy(new FakeStrategy(Market.ET, "FAKE_ET", fakeEt));
    }

    @Bean
    public BillingStrategy usStrategy(FakeProviderClient fakeUs) {
        return new UsBillingStrategy(new FakeStrategy(Market.US, "FAKE_US", fakeUs));
    }
}
