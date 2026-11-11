package com.agafari.com.billing.stripe;

import com.agafari.com.billing.BillingStrategyRegistry;
import com.agafari.com.billing.Market;
import com.agafari.com.billing.PaymentOutcomeService;
import com.agafari.com.billing.ProviderActivationReadiness;
import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF SUB-R5-01 (PO Q5): the whole application with Stripe switched on and synthetic test-mode credentials in its OS
 * environment. No credential value appears in health, actuator, error or webhook responses, or in the log; billing stays
 * refused; the US market resolves to the Stripe scaffolding, which calls nothing.
 */
@TestPropertySource(properties = "app.billing.stripe.enabled=true")
@ContextConfiguration(initializers = StripeSecretExposureTest.SyntheticStripeEnvironment.class)
@ExtendWith(OutputCaptureExtension.class)
class StripeSecretExposureTest extends AbstractIntegrationTest {

    static final String KEY = "sk_" + "test_" + "SyntheticR501Exposure00000001";
    static final String WEBHOOK = "whsec_" + "SyntheticR501Exposure00000002";
    static final String PREVIOUS = "whsec_" + "SyntheticR501Exposure00000003";

    /** The real OS environment (the test database settings live there) plus the three synthetic Stripe variables. */
    static class SyntheticStripeEnvironment implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            Map<String, Object> env = new HashMap<>(System.getenv());
            env.put(StripeCredentials.ENV_SECRET_KEY, KEY);
            env.put(StripeCredentials.ENV_WEBHOOK_SECRET, WEBHOOK);
            env.put(StripeCredentials.ENV_WEBHOOK_SECRET_PREVIOUS, PREVIOUS);
            ctx.getEnvironment().getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
        }
    }

    @Autowired private BillingStrategyRegistry registry;
    @Autowired private PaymentOutcomeService outcomes;

    private static void assertNoSecret(String text) {
        for (String v : List.of(KEY, WEBHOOK, PREVIOUS)) assertThat(text).doesNotContain(v).doesNotContain(v.substring(8));
    }

    private MockHttpServletResponse call(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    @Test
    void theUsMarketResolvesToTheStripeScaffoldingAndBillingStaysRefused() {
        assertThat(registry.forMarket(Market.US).provider()).isEqualTo("STRIPE");
        assertThat(registry.forProvider("STRIPE")).isPresent();
        assertThat(outcomes.billingActive(Market.US)).as("no billing without the gates").isFalse();
        assertThat(ProviderActivationReadiness.ready()).isFalse();
    }

    @Test
    void healthAndActuatorNeverShowACredential(CapturedOutput output) throws Exception {
        MockHttpServletResponse health = call(get("/actuator/health"));
        assertThat(health.getStatus()).isEqualTo(200);
        assertNoSecret(health.getContentAsString());
        for (String path : List.of("/actuator/env", "/actuator/env/" + StripeCredentials.ENV_SECRET_KEY, "/actuator/configprops", "/actuator/beans", "/actuator/info")) {
            MockHttpServletResponse r = call(get(path));
            assertThat(r.getStatus()).as(path + " is not exposed with values").isNotEqualTo(200).isGreaterThanOrEqualTo(400);
            assertNoSecret(r.getContentAsString());
        }
        assertNoSecret(output.getAll());
    }

    @Test
    void errorAndWebhookResponsesNeverShowACredential(CapturedOutput output) throws Exception {
        byte[] body = "{\"id\":\"evt_r501\",\"type\":\"checkout.session.completed\"}".getBytes();
        MockHttpServletResponse webhook = call(post("/api/billing/webhooks/STRIPE").contentType(MediaType.APPLICATION_JSON).content(body).header("Stripe-Signature", "t=1,v1=synthetic"));
        assertThat(webhook.getStatus()).as("billing is off for the US market: no route").isEqualTo(404);
        assertNoSecret(webhook.getContentAsString());
        MockHttpServletResponse missing = call(get("/api/billing/checkouts/unknown"));
        assertThat(missing.getStatus()).isGreaterThanOrEqualTo(400);
        assertNoSecret(missing.getContentAsString());
        assertNoSecret(output.getAll());
    }
}
