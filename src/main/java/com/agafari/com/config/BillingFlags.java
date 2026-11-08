package com.agafari.com.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AGF SUB-R2-12 (Migration §6): the billing and enforcement flags, all off by default and set nowhere. {@code period-end-enabled}
 * is global; the others are per market. The two markers are recorded by the Product Owner: PD-9 decided (R-3), and the
 * period-end report acknowledged.
 */
@ConfigurationProperties("app.billing")
public record BillingFlags(@DefaultValue("false") boolean periodEndEnabled, @DefaultValue("false") boolean pd9Decided,
                           @DefaultValue("false") boolean periodEndReportAcknowledged, @DefaultValue MarketFlags us, @DefaultValue MarketFlags et) {

    public record MarketFlags(@DefaultValue("false") boolean pricesVisible, @DefaultValue("false") boolean billingEnabled,
                              @DefaultValue("false") boolean publishRequiresPlan, @DefaultValue("false") boolean guestEnforcement) {
    }

    public boolean billingEnabledInAnyMarket() {
        return us.billingEnabled() || et.billingEnabled();
    }
}
