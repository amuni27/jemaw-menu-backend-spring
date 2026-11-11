package com.agafari.com.billing.stripe;

import com.agafari.com.billing.UsBillingStrategy;
import com.agafari.com.billing.port.BillingStrategy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * AGF SUB-R5-01: wires the Stripe adapter as the US market's strategy, <b>only</b> when {@code app.billing.stripe.enabled=true}
 * (default: off, so nothing Stripe-related exists and no credential is needed). Credentials come from the operating-system
 * environment only, never from application properties (PO Q5). Wiring changes nothing else: {@code billing-enabled} stays
 * refused while the activation gates are open, and every Stripe operation refuses until its story is authorized.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.billing.stripe", name = "enabled", havingValue = "true")
public class StripeConfiguration {

    @Bean
    StripeCredentials stripeCredentials(ConfigurableEnvironment environment) {
        PropertySource<?> os = environment.getPropertySources().get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        return StripeCredentials.fromEnvironment(name -> {
            Object value = os == null ? null : os.getProperty(name);
            return value == null ? null : value.toString();
        });
    }

    @Bean
    StripeProviderClient stripeProviderClient(StripeCredentials credentials) {
        return new StripeProviderClient(credentials);
    }

    @Bean
    BillingStrategy stripeUsStrategy(StripeProviderClient client) {
        return new UsBillingStrategy(new StripeBillingStrategy(client));
    }
}
