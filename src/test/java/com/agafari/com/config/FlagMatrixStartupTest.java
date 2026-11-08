package com.agafari.com.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** AGF SUB-R2-12: each combination, through a real Spring startup. */
class FlagMatrixStartupTest {

    private static DataSource database(String product) throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection c = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);
        when(ds.getConnection()).thenReturn(c);
        when(c.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn(product);
        return ds;
    }

    private static ApplicationContextRunner on(String product) throws Exception {
        DataSource ds = database(product);
        return new ApplicationContextRunner().withBean(DataSource.class, () -> ds).withUserConfiguration(FlagMatrixValidator.class);
    }

    private static ApplicationContextRunner postgres() throws Exception {
        return on("PostgreSQL");
    }

    private static ApplicationContextRunner h2() throws Exception {
        return on("H2");
    }

    private static void refused(ApplicationContextRunner runner, String expected, String... properties) {
        runner.withPropertyValues(properties).run(c -> {
            assertThat(c).hasFailed();
            assertThat(c.getStartupFailure()).isInstanceOf(FlagMatrixViolationException.class).hasMessageContaining(expected);
        });
    }

    private static void starts(ApplicationContextRunner runner, String... properties) {
        runner.withPropertyValues(properties).run(c -> assertThat(c).hasNotFailed());
    }

    @Test
    void defaultsStartOnPostgresAndH2AndAreAllOff() throws Exception {
        starts(h2());
        postgres().run(c -> {
            assertThat(c).hasNotFailed();
            BillingFlags f = c.getBean(BillingFlags.class);
            assertThat(f.periodEndEnabled()).isFalse();
            assertThat(f.pd9Decided()).isFalse();
            assertThat(f.periodEndReportAcknowledged()).isFalse();
            assertThat(f.billingEnabledInAnyMarket()).isFalse();
            assertThat(f.us()).isEqualTo(new BillingFlags.MarketFlags(false, false, false, false));
            assertThat(f.et()).isEqualTo(new BillingFlags.MarketFlags(false, false, false, false));
        });
    }

    @Test
    void periodEndStartsOnlyAcknowledged() throws Exception {
        refused(postgres(), "acknowledged", "app.billing.period-end-enabled=true");
        starts(postgres(), "app.billing.period-end-enabled=true", "app.billing.period-end-report-acknowledged=true");
    }

    @Test
    void periodEndIsRefusedOnH2() throws Exception {
        refused(h2(), "PostgreSQL", "app.billing.period-end-enabled=true", "app.billing.period-end-report-acknowledged=true");
    }

    @Test
    void pricesAreRefusedToday() throws Exception {
        refused(postgres(), "prices-visible", "app.billing.us.prices-visible=true");
        refused(postgres(), "ET", "app.billing.et.prices-visible=true");
    }

    @Test
    void pricesVisibleFollowsRealAvailabilityPerMarket() throws Exception {
        com.agafari.com.billing.PriceAvailability onlyUs = market -> market == com.agafari.com.billing.Market.US;
        ApplicationContextRunner withPrices = postgres().withBean(com.agafari.com.billing.PriceAvailability.class, () -> onlyUs);
        starts(withPrices, "app.billing.us.prices-visible=true");
        refused(withPrices, "ET", "app.billing.et.prices-visible=true");
    }

    @Test
    void billingIsRefused() throws Exception {
        refused(postgres(), "PD-9", "app.billing.us.billing-enabled=true");
        refused(h2(), "PostgreSQL", "app.billing.et.billing-enabled=true");
    }

    @Test
    void publishEnforcementIsRefused() throws Exception {
        refused(postgres(), "dry run", "app.billing.us.publish-requires-plan=true", "app.billing.period-end-enabled=true", "app.billing.period-end-report-acknowledged=true");
        refused(h2(), "PostgreSQL", "app.billing.us.publish-requires-plan=true");
    }

    /** NC-11. */
    @Test
    void guestEnforcementWithoutALegacyRunIsRefused() throws Exception {
        refused(postgres(), "legacy run", "app.billing.us.guest-enforcement=true");
        refused(h2(), "PostgreSQL", "app.billing.et.guest-enforcement=true");
    }

    @Test
    void jobsAreRefusedOnH2AndStartOnPostgres() throws Exception {
        refused(h2(), "app.jobs.enabled", "app.jobs.enabled=true");
        starts(postgres(), "app.jobs.enabled=true");
    }
}
