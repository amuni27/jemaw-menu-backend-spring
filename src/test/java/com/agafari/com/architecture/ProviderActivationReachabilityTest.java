package com.agafari.com.architecture;

import com.agafari.com.billing.BillingStrategyRegistry;
import com.agafari.com.billing.InboxProcessHandler;
import com.agafari.com.billing.Market;
import com.agafari.com.billing.PaymentOutcomeService;
import com.agafari.com.billing.port.InboundEvent;
import com.agafari.com.billing.webhook.InboxProcessPayload;
import com.agafari.com.billing.webhook.InboxService;
import com.agafari.com.jobs.JobHandler;
import com.agafari.com.jobs.PermanentJobException;
import com.agafari.com.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGF SUB-R4-10 (PO ruling R3): the production application context, with no test provider. The {@code inbox.process}
 * handler now exists, yet a stored event activates nothing, writes nothing financial and stays visible as unprocessed.
 */
class ProviderActivationReachabilityTest extends AbstractIntegrationTest {

    @Autowired private ApplicationContext context;
    @Autowired private BillingStrategyRegistry registry;
    @Autowired private PaymentOutcomeService outcomes;
    @Autowired private InboxProcessHandler handler;
    @Autowired private InboxService inbox;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void theInboxHandlerIsTheOnlyHandlerOfItsJobAndNoProviderResolves() {
        List<Object> inboxHandlers = context.getBeansOfType(JobHandler.class).values().stream()
                .filter(h -> ((JobHandler<?>) h).type().equals(InboxService.JOB_TYPE)).map(h -> (Object) h).toList();
        assertThat(inboxHandlers).containsExactly(handler);
        for (String provider : List.of("STRIPE", "CHAPA", "FAKE_ET", "FAKE_US")) assertThat(registry.forProvider(provider)).as(provider).isEmpty();
        assertThat(outcomes.billingActive(Market.US)).isFalse();
        assertThat(outcomes.billingActive(Market.ET)).isFalse();
    }

    @Test
    void aStoredEventWithoutAProviderStrategyActivatesNothingAndStaysVisible() {
        inbox.store("STRIPE", new InboundEvent("evt_unreachable", "payment.succeeded"),
                "{\"id\":\"evt_unreachable\",\"payment_ref\":\"pi_1\"}".getBytes(StandardCharsets.UTF_8));
        String id = jdbc.queryForObject("select id from provider_event where event_id = 'evt_unreachable'", String.class);

        assertThatThrownBy(() -> handler.handle(new InboxProcessPayload(id))).isInstanceOf(PermanentJobException.class);

        assertThat(jdbc.queryForObject("select status from provider_event where id = ?", String.class, id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select last_error from provider_event where id = ?", String.class, id)).contains("not active");
        for (String table : List.of("payment", "billing_invoice", "subscription_period", "billing_exception", "refund_request")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("select count(*) from business_subscription where source = 'PROVIDER'", Integer.class)).isZero();
    }
}
