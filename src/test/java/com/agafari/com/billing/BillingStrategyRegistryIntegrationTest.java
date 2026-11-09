package com.agafari.com.billing;

import com.agafari.com.billing.fake.FakeStrategy;
import com.agafari.com.billing.port.BillingStrategy;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AGF SUB-R4-04: against the real billing_account table, and the production bean with no strategies registered. */
class BillingStrategyRegistryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private BillingStrategyRegistry productionRegistry;

    private final FakeStrategy us = new FakeStrategy(Market.US);
    private final FakeStrategy et = new FakeStrategy(Market.ET);

    private BillingStrategyRegistry registry() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("us", us);
        beans.addBean("et", et);
        return new BillingStrategyRegistry(beans.getBeanProvider(BillingStrategy.class), jdbc);
    }

    private void account(String business, String market, String provider) {
        jdbc.update("insert into billing_account (id, business_id, market, provider) values (?, ?, ?, ?)", UUID.randomUUID().toString(), business, market, provider);
    }

    @Test
    void eachRestaurantGetsItsOwnMarketsStrategy() {
        Tenant ethiopian = data.tenant("et-owner");
        Tenant american = data.tenant("us-owner");
        jdbc.update("update business set country = 'US' where id = ?", american.businessId());
        account(ethiopian.businessId(), "ET", "P1");
        account(american.businessId(), "US", "P1");
        assertThat(registry().forBusiness(ethiopian.businessId())).isSameAs(et);
        assertThat(registry().forBusiness(american.businessId())).isSameAs(us);
    }

    @Test
    void severalAccountsInOneMarketResolveOnce() {
        Tenant t = data.tenant("two-providers");
        account(t.businessId(), "ET", "P1");
        account(t.businessId(), "ET", "P2");
        assertThat(registry().forBusiness(t.businessId())).isSameAs(et);
    }

    @Test
    void aRestaurantWithoutAnAccountIsRefused() {
        Tenant t = data.tenant("no-account");
        assertThatThrownBy(() -> registry().forBusiness(t.businessId())).hasMessageContaining("no billing account");
    }

    @Test
    void productionHasNoStrategyRegisteredYet() {
        assertThatThrownBy(() -> productionRegistry.forMarket(Market.ET)).hasMessageContaining("No billing strategy");
    }
}
