package com.agafari.com.billing;

import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.jobs.PermanentJobException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AGF SUB-R4-10: with a provider present but its market's billing off, an event is never processed and the provider is never asked. */
class PaymentOutcomeGateTest extends PaymentOutcomeTestBase {

    @Test
    void withTheMarketsBillingOffAnEventActivatesNothingAndStaysPending() {
        String e = event(paid(checkout(owner, CheckoutKind.NEW, null), Instant.now().plus(1, ChronoUnit.MINUTES)));
        outcomes.setBillingEnabled(Market.ET, false);

        assertThatThrownBy(() -> process(e)).isInstanceOf(PermanentJobException.class);

        assertThat(eventRow(e).get("status")).isEqualTo("PENDING");
        assertThat(fakeEt.verifyCalls()).isZero();
        assertThat(count("select count(*) from payment")).isZero();
        assertThat(count("select count(*) from billing_exception")).isZero();
        assertThat(current(owner)).isNull();
    }

    @Test
    void anEventThatNamesNoPaymentIsNeverProcessedAsOne() {
        String eventId = "evt_no_ref";
        inbox.store(PROVIDER, new com.agafari.com.billing.port.InboundEvent(eventId, "customer.updated"), ("{\"id\":\"" + eventId + "\"}").getBytes());
        String e = jdbc.queryForObject("select id from provider_event where event_id = ?", String.class, eventId);

        assertThatThrownBy(() -> process(e)).isInstanceOf(PermanentJobException.class);

        assertThat(eventRow(e).get("status")).as("unprocessed and visible, never PROCESSED or IGNORED").isEqualTo("PENDING");
        assertThat(fakeEt.verifyCalls()).isZero();
    }
}
