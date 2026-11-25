package com.agafari.com.billing.stripe;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agafari.com.billing.InboxProcessHandler;
import com.agafari.com.billing.Market;
import com.agafari.com.billing.PaymentOutcomeService;
import com.agafari.com.billing.webhook.WebhookController;
import com.agafari.com.jobs.JobRunner;
import com.agafari.com.jobs.PermanentJobException;
import com.agafari.com.support.AbstractIntegrationTest;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF BILLING-UNSUPPORTED-EVENTS (register §96 U-1..U-3) on PostgreSQL: the real webhook intake, the Stripe adapter's
 * signature check (<b>synthetic</b> secret), the inbox and the real job runner. No network; no real Stripe account.
 * <ul>
 *   <li>U-1: a signed event of a type the provider doesn't handle is kept with its payload and finished <b>IGNORED</b> on
 *   its first run: no retry, no DEAD job, no exception, no money effect.</li>
 *   <li>U-2: one INFO line per ignored event, counting earlier ignored events of the same provider and type.</li>
 *   <li>U-3: known provider codes match the route ignoring ASCII case; anything else is still no route.</li>
 * </ul>
 */
@Import(StripeStubBillingConfig.class)
class UnsupportedWebhookEventsTest extends AbstractIntegrationTest {

    private static final String ROUTE = "/api/billing/webhooks/";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PaymentOutcomeService outcomes;
    @Autowired private WebhookController webhooks;
    @Autowired private InboxProcessHandler inboxHandler;
    @Autowired private StripeProviderClient stripe;
    @Autowired private StripeStub stub;
    @Autowired private com.agafari.com.billing.webhook.InboxService inbox;
    @Autowired private ObjectMapper mapper;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private JobRunner runner;

    @BeforeEach
    void setUp() {
        stub.reset();
        outcomes.setBillingEnabled(Market.US, true);
        webhooks.setBillingEnabled(Market.US, true);
        appender.start();
        ((Logger) LoggerFactory.getLogger(PaymentOutcomeService.class)).addAppender(appender);
        runner = new JobRunner(jdbc, List.of(inboxHandler), mapper, 2, Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        runner.shutdown();
        ((Logger) LoggerFactory.getLogger(PaymentOutcomeService.class)).detachAppender(appender);
        outcomes.setBillingEnabled(Market.US, false);
        webhooks.setBillingEnabled(Market.US, false);
    }

    // ---- U-1: unsupported types are IGNORED, once ----

    @Test
    void anUnsupportedStripeTypeIsIgnoredOnItsFirstRunAndKeptWithItsPayload() throws Exception {
        String payload = subscriptionCreated();
        String id = deliver("STRIPE", payload);

        runJobsOnce();

        Map<String, Object> e = event(id);
        assertThat(e.get("status")).isEqualTo("IGNORED");
        assertThat((String) e.get("last_error")).startsWith("UNSUPPORTED_EVENT_TYPE: customer.subscription.created");
        assertThat(e.get("processed_at")).as("finished").isNotNull();
        assertThat(e.get("attempts")).isEqualTo(1);
        assertThat(e.get("type")).isEqualTo("customer.subscription.created");
        assertThat(mapper.readTree((String) e.get("payload"))).as("the verified payload is kept").isEqualTo(mapper.readTree(payload));

        Map<String, Object> job = jdbc.queryForMap("select status, claims, last_error from job where type = 'inbox.process' and payload ->> 'providerEventId' = ?", id);
        assertThat(job.get("status")).as("no retry, no DEAD job").isEqualTo("DONE");
        assertThat(job.get("claims")).isEqualTo(1);
        assertThat(job.get("last_error")).isNull();

        assertThat(count("select count(*) from payment")).isZero();
        assertThat(count("select count(*) from billing_invoice")).isZero();
        assertThat(count("select count(*) from business_subscription")).isZero();
        assertThat(count("select count(*) from billing_exception")).as("no exception").isZero();
    }

    @Test
    void anyTypeOutsideTheHandledListIsIgnoredNotRetried() throws Exception {
        String invented = deliver("STRIPE", StripeFixtures.event("agafari.invented.type", Map.of("id", "obj_synthetic", "object", "thing")));
        String expired = deliver("STRIPE", StripeFixtures.event("checkout.session.expired", Map.of("id", "cs_synthetic_expired", "object", "checkout.session")));

        runJobsOnce();

        assertThat(event(invented).get("status")).isEqualTo("IGNORED");
        assertThat(event(expired).get("status")).isEqualTo("IGNORED");
        assertThat(count("select count(*) from job where type = 'inbox.process' and status <> 'DONE'")).isZero();
    }

    @Test
    void theStripeAdapterHandlesExactlyTheFiveTypesAgafariProcesses() {
        var verifier = stripe.signatureVerifier();
        for (String handled : List.of("checkout.session.completed", "invoice.paid", "invoice.payment_failed", "customer.subscription.updated", "customer.subscription.deleted")) {
            assertThat(verifier.handlesEventType(handled)).as(handled).isTrue();
        }
        for (String other : List.of("customer.subscription.created", "checkout.session.expired", "invoice.created", "payment_intent.succeeded", "CHECKOUT.SESSION.COMPLETED", "", "invoice.paid ")) {
            assertThat(verifier.handlesEventType(other)).as("'" + other + "'").isFalse();
        }
    }

    @Test
    void theRotatingVerifierForwardsEveryVerifierMethod() {
        // a default method the wrapper doesn't override would silently answer for every provider (found by this story)
        for (java.lang.reflect.Method m : com.agafari.com.billing.port.SignatureVerifier.class.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            assertThat(java.util.Arrays.stream(com.agafari.com.billing.port.RotatingSignatureVerifier.class.getDeclaredMethods())
                    .anyMatch(d -> d.getName().equals(m.getName()) && java.util.Arrays.equals(d.getParameterTypes(), m.getParameterTypes())))
                    .as("RotatingSignatureVerifier overrides " + m.getName()).isTrue();
        }
        var rotating = new com.agafari.com.billing.port.RotatingSignatureVerifier(stripeVerifierOnly(), null);
        assertThat(rotating.handlesEventType("invoice.paid")).isTrue();
        assertThat(rotating.handlesEventType("customer.subscription.created")).isFalse();
    }

    private static com.agafari.com.billing.port.SignatureVerifier stripeVerifierOnly() {
        return new com.agafari.com.billing.port.SignatureVerifier() {
            @Override public boolean verify(Map<String, String> headers, byte[] rawBody) { return false; }
            @Override public com.agafari.com.billing.port.InboundEvent identify(byte[] rawBody) { throw new UnsupportedOperationException(); }
            @Override public boolean handlesEventType(String type) { return StripeSignatureVerifier.HANDLED_EVENT_TYPES.contains(type); }
        };
    }

    @Test
    void aDuplicateUnsupportedEventIsStoredOnceAndIgnoredOnce() throws Exception {
        String payload = subscriptionCreated();
        String id = deliver("STRIPE", payload);
        assertThat(deliver("STRIPE", payload)).as("the same stored row").isEqualTo(id);
        assertThat(count("select count(*) from provider_event")).isEqualTo(1);

        assertThat(outcomes.process(id)).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(outcomes.process(id)).as("a second run changes nothing").isEqualTo(PaymentOutcomeService.Result.ALREADY_DONE);
        assertThat(event(id)).containsEntry("status", "IGNORED").containsEntry("attempts", 1);
        assertThat(ignoredLines()).hasSize(1);
    }

    @Test
    void aReplayOfAnIgnoredEventIsAlreadyDone() throws Exception {
        String id = deliver("STRIPE", subscriptionCreated());
        outcomes.process(id);

        assertThat(outcomes.replay(id)).isEqualTo(PaymentOutcomeService.Result.ALREADY_DONE);
        assertThat(event(id)).containsEntry("status", "IGNORED").containsEntry("attempts", 1);
    }

    @Test
    void aHandledTypeThatNamesNothingStillFailsPermanently() throws Exception {
        String id = deliver("STRIPE", StripeFixtures.event("checkout.session.completed", Map.of("id", "cs_synthetic_noinvoice", "object", "checkout.session")));

        assertThatThrownBy(() -> outcomes.process(id)).isInstanceOf(PermanentJobException.class).hasMessageContaining("names no payment");
        assertThat(event(id).get("status")).as("unexpected for a handled type: left PENDING and visible, as before").isEqualTo("PENDING");
        assertThat(ignoredLines()).isEmpty();
    }

    @Test
    void whileBillingIsNotActiveAnUnsupportedEventIsLeftPendingAsBefore() throws Exception {
        String id = deliver("STRIPE", subscriptionCreated());
        outcomes.setBillingEnabled(Market.US, false);

        assertThatThrownBy(() -> outcomes.process(id)).isInstanceOf(PermanentJobException.class).hasMessageContaining("billing is not active");
        assertThat(event(id).get("status")).isEqualTo("PENDING");
    }

    @Test
    void anInternalReconciliationEventIsNeverIgnoredUnderStripesHandledTypes() {
        // its type (agafari.reconciliation.payment) is in no provider's list: it must still reach the re-fetch (found by NC-U14)
        String id = inbox.storeInternal("STRIPE", "in_synthetic_reconciled");
        try {
            outcomes.process(id);
        } catch (RuntimeException expected) {
            // the stub has no such invoice; only the re-fetch matters here
        }
        assertThat(stub.calls().stream().map(StripeStub.Call::path).toList()).as("the reconciliation re-fetch was attempted").contains("/v1/invoices/in_synthetic_reconciled");
        assertThat(event(id).get("status")).isNotEqualTo("IGNORED");
        assertThat(ignoredLines()).isEmpty();
    }

    // ---- U-2: INFO, and repeats are counted ----

    @Test
    void eachIgnoredEventLogsOneInfoLineCountingRepeatsOfItsType() throws Exception {
        String first = deliver("STRIPE", subscriptionCreated());
        String second = deliver("STRIPE", subscriptionCreated());
        String other = deliver("STRIPE", StripeFixtures.event("invoice.created", Map.of("id", "in_synthetic_created", "object", "invoice")));

        outcomes.process(first);
        outcomes.process(second);
        outcomes.process(other);

        List<ILoggingEvent> lines = appender.list.stream().filter(l -> l.getFormattedMessage().startsWith("event=billing.webhook_ignored")).toList();
        assertThat(lines).hasSize(3);
        assertThat(lines).allSatisfy(l -> assertThat(l.getLevel().toString()).isEqualTo("INFO"));
        String eventId1 = (String) event(first).get("event_id"), eventId2 = (String) event(second).get("event_id");
        assertThat(lines.get(0).getFormattedMessage()).isEqualTo("event=billing.webhook_ignored provider=STRIPE type=customer.subscription.created eventId=" + eventId1 + " occurrences=1");
        assertThat(lines.get(1).getFormattedMessage()).isEqualTo("event=billing.webhook_ignored provider=STRIPE type=customer.subscription.created eventId=" + eventId2 + " occurrences=2");
        assertThat(lines.get(2).getFormattedMessage()).as("counted per type").endsWith(" occurrences=1");
        assertThat(String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())).as("never the body").doesNotContain("cus_synthetic_secretish");
    }

    // ---- U-3: known provider codes, any ASCII case ----

    @Test
    void knownProviderCodesMatchTheRouteInAnyAsciiCase() throws Exception {
        for (String path : List.of("stripe", "Stripe", "sTrIpE", "STRIPE")) {
            String id = deliver(path, subscriptionCreated());
            assertThat(event(id).get("provider")).as(path + " is stored under the canonical code").isEqualTo("STRIPE");
        }
        assertThat(count("select count(*) from provider_event where provider = 'STRIPE'")).isEqualTo(4);
    }

    @Test
    void unknownProvidersAndLookalikesAreStillNoRoute() throws Exception {
        String payload = subscriptionCreated();
        for (String path : List.of("stripex", "strip", "paypal", "chapa_unknown", "ſtripe", "strıpe", "STRİPE", "ＳTRIPE")) {
            assertThat(status(path, payload, signature(payload))).as("'" + path + "'").isEqualTo(404);
        }
        assertThat(count("select count(*) from provider_event")).isZero();
    }

    @Test
    void theLowercaseRouteStillRequiresAValidSignature() throws Exception {
        String payload = subscriptionCreated();
        assertThat(status("stripe", payload, "t=1,v1=forged")).isEqualTo(400);
        assertThat(status("stripe", payload, null)).isEqualTo(400);
        assertThat(status("stripe", payload, Webhook.Signature.generateSignatureHeader(payload, "whsec_" + "SyntheticWrongSecret000000000"))).isEqualTo(400);
        assertThat(count("select count(*) from provider_event")).as("nothing stored").isZero();
    }

    @Test
    void theLowercaseRouteIsStillNoRouteWhileBillingIsOff() throws Exception {
        webhooks.setBillingEnabled(Market.US, false);
        String payload = subscriptionCreated();
        assertThat(status("stripe", payload, signature(payload))).isEqualTo(404);
        assertThat(status("STRIPE", payload, signature(payload))).isEqualTo(404);
        assertThat(count("select count(*) from provider_event")).isZero();
    }

    // ---- helpers ----

    private static String subscriptionCreated() {
        Map<String, Object> sub = new LinkedHashMap<>();
        sub.put("id", StripeFixtures.id("sub"));
        sub.put("object", "subscription");
        sub.put("status", "active");
        sub.put("customer", "cus_synthetic_secretish");
        return StripeFixtures.event("customer.subscription.created", sub);
    }

    private static String signature(String payload) throws Exception {
        return Webhook.Signature.generateSignatureHeader(payload, StripeStubBillingConfig.WEBHOOK_SECRET);
    }

    private int status(String provider, String payload, String signatureHeader) throws Exception {
        var request = post(ROUTE + "{provider}", provider).contentType(MediaType.APPLICATION_JSON).content(payload);
        if (signatureHeader != null) request.header("Stripe-Signature", signatureHeader);
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    /** Delivers a signed event (synthetic secret) through the real webhook on {@code provider}; returns the stored row id. */
    private String deliver(String provider, String payload) throws Exception {
        assertThat(status(provider, payload, signature(payload))).isEqualTo(200);
        String eventId = mapper.readTree(payload).path("id").asString();
        return jdbc.queryForObject("select id from provider_event where event_id = ?", String.class, eventId);
    }

    private void runJobsOnce() throws Exception {
        for (Future<?> f : runner.pollOnce()) f.get(30, TimeUnit.SECONDS);
    }

    private Map<String, Object> event(String id) {
        return jdbc.queryForMap("select provider, event_id, type, payload::text as payload, status, attempts, processed_at, last_error from provider_event where id = ?", id);
    }

    private List<String> ignoredLines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.startsWith("event=billing.webhook_ignored")).toList();
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
