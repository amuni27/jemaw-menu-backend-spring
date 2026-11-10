package com.agafari.com.billing;

import com.agafari.com.billing.fake.FakeProviderClient;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.webhook.WebhookController;
import com.agafari.com.jobs.JobFailurePolicy;
import com.agafari.com.jobs.JobHandler;
import com.agafari.com.jobs.JobRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** AGF SUB-R4-10: the real paths around the outcome service: owner checkout, browser return, signed webhook, job runner. */
class PaymentOutcomeFlowTest extends PaymentOutcomeTestBase {

    @Autowired private WebhookController webhooks;
    @Autowired private InboxProcessHandler handler;
    @Autowired private ObjectMapper mapper;

    @BeforeEach
    void enableWebhooks() {
        webhooks.setBillingEnabled(Market.ET, true);
    }

    @AfterEach
    void disableWebhooks() {
        webhooks.setBillingEnabled(Market.ET, false);
    }

    @Test
    void aBrowserReturnWithoutAnEventNeverActivatesAndTheVerifiedEventDoes() throws Exception {
        assertThat(httpStatus(owner, "k-return", price)).isEqualTo(201);
        String c = (String) only().get("id");
        String ref = paid(c, Instant.now().plus(1, ChronoUnit.MINUTES));

        // The return carries what a provider redirect might: it is never evidence.
        int returned = mockMvc.perform(get("/api/billing/checkouts/" + c).param("payment_ref", ref).header("Authorization", data.bearer(owner))).andReturn().getResponse().getStatus();
        assertThat(returned).isEqualTo(200);
        assertThat(current(owner)).as("NC-04: the return activates nothing").isNull();
        assertThat(payments(owner)).isZero();
        assertThat(fakeEt.verifyCalls()).isZero();

        assertThat(webhook("evt_return", ref)).isEqualTo(200);
        String e = jdbc.queryForObject("select id from provider_event where event_id = 'evt_return'", String.class);
        handler.handle(new com.agafari.com.billing.webhook.InboxProcessPayload(e));

        assertThat(providerSubscriptions(owner)).isEqualTo(1);
        assertThat(checkoutStatus(c)).isEqualTo("COMPLETED");
    }

    @Test
    void aDuplicateWebhookIsStoredOnceAndProcessedOnce() throws Exception {
        String ref = paid(checkout(owner, CheckoutKind.NEW, null), Instant.now().plus(1, ChronoUnit.MINUTES));

        assertThat(webhook("evt_dup", ref)).isEqualTo(200);
        assertThat(webhook("evt_dup", ref)).isEqualTo(200);
        assertThat(count("select count(*) from provider_event where event_id = 'evt_dup'")).isEqualTo(1);
        assertThat(count("select count(*) from job where type = 'inbox.process'")).isEqualTo(1);

        assertThat(runAll()).isEqualTo(1);
        assertThat(runAll()).as("nothing is left to claim").isZero();

        assertThat(jdbc.queryForObject("select status from job where type = 'inbox.process'", String.class)).isEqualTo("DONE");
        assertThat(payments(owner)).isEqualTo(1);
        assertThat(periods(owner)).isEqualTo(1);
    }

    @Test
    void anUnresolvedPaymentAtTheRetryCeilingStaysPendingWithoutAnExceptionAnOpenReleaseBlocker() throws Exception {
        fakeEt.script(FakeProviderClient.Script.DELAY).delayedFetches(1000);
        String ref = paid(checkout(owner, CheckoutKind.NEW, null), Instant.now().plus(1, ChronoUnit.MINUTES));
        assertThat(webhook("evt_pending", ref)).isEqualTo(200);

        for (int i = 0; i < JobFailurePolicy.MAX_CLAIMS; i++) {
            assertThat(runAll()).isEqualTo(1);
            jdbc.update("update job set run_at = now() - interval '1 second' where type = 'inbox.process' and status = 'PENDING'");
        }

        Map<String, Object> job = jdbc.queryForMap("select status, claims, last_error_kind from job where type = 'inbox.process'");
        assertThat(job).containsEntry("status", "DEAD").containsEntry("claims", JobFailurePolicy.MAX_CLAIMS).containsEntry("last_error_kind", "TRANSIENT");
        Map<String, Object> event = jdbc.queryForMap("select status, attempts, last_error from provider_event where event_id = 'evt_pending'");
        assertThat(event).containsEntry("status", "PENDING").containsEntry("attempts", JobFailurePolicy.MAX_CLAIMS);
        assertThat((String) event.get("last_error")).contains("UNRESOLVED");
        // R7b: no durable exception exists for this case yet. This is recorded as an open release blocker, not a pass.
        assertThat(count("select count(*) from billing_exception")).isZero();
        assertThat(count("select count(*) from payment")).isZero();
        assertThat(current(owner)).isNull();
    }

    private int webhook(String eventId, String paymentRef) throws Exception {
        byte[] body = body(eventId, paymentRef);
        return mockMvc.perform(post("/api/billing/webhooks/" + PROVIDER).contentType(MediaType.APPLICATION_JSON).content(body)
                .header(FakeProviderClient.SIGNATURE_HEADER, FakeProviderClient.sign(body))).andReturn().getResponse().getStatus();
    }

    /** One poll of a runner that knows only the inbox handler; returns how many jobs it ran. */
    private int runAll() throws Exception {
        JobRunner runner = new JobRunner(jdbc, List.<JobHandler<?>>of(handler), mapper, 1, Clock.systemUTC());
        try {
            List<Future<?>> started = runner.pollOnce();
            for (Future<?> f : started) f.get(60, TimeUnit.SECONDS);
            return started.size();
        } finally {
            runner.shutdown();
        }
    }
}
