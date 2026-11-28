package com.agafari.com.billing;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.InboundEvent;
import com.agafari.com.billing.port.ProviderBilling;
import com.agafari.com.billing.port.ProviderInvoiceState;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF gate 3 (register §89 G3-B..E, §101, §102) on PostgreSQL with the fake provider: a <b>verified successful</b> payment
 * that can't be attributed to a restaurant is quarantined <b>together with</b> exactly one restaurant-less
 * {@code UNATTRIBUTED_PAYMENT} per payment (V23_7 identity), whichever webhooks or reconciliation runs deliver it; never a
 * restaurant inferred, never an activation, refund or ledger row. P-R4 renewal mismatches are unchanged (C-1); failed
 * payments and state notifications are never money (C-2); a payment-path verification failure gets an unverified-outcome
 * review exception (K-1). 24 h overdue and 48 h status-only escalation; attribution only by replaying Stripe evidence; a
 * funds-handled-externally resolution needs the Stripe refund reference.
 */
class UnattributedPaymentTest extends PaymentOutcomeTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final String UNATTRIBUTED = "UNATTRIBUTED_PAYMENT";
    private static final String PENDING_REVIEW = "PAYMENT_OUTCOME_PENDING_REVIEW";

    @Autowired private ExceptionSla sla;
    @Autowired private ObjectMapper mapper;
    @Autowired private com.agafari.com.subscription.EffectiveStateEvaluator evaluator;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        ((Logger) LoggerFactory.getLogger(ExceptionService.class)).addAppender(logs);
        ((Logger) LoggerFactory.getLogger(ExceptionSla.class)).addAppender(logs);
    }

    @AfterEach
    void stopCapture() {
        ((Logger) LoggerFactory.getLogger(ExceptionService.class)).detachAppender(logs);
        ((Logger) LoggerFactory.getLogger(ExceptionSla.class)).detachAppender(logs);
        clock.reset();
        evaluator.setPeriodEndEnabled(false);
    }

    // ---- the unattributable cases (G3-F as narrowed by C-1) ----

    @Test
    void aVerifiedPaymentWithNoAgafariReferenceIsQuarantinedWithOneRestaurantlessExceptionInTheSameCommit() {
        Instant tPay = micros(Instant.now());
        String ref = verified(tPay, Map.of());
        LedgerRows before = ledger();

        String event = event(ref);
        assertThat(process(event)).isEqualTo(PaymentOutcomeService.Result.QUARANTINED);

        assertThat((String) eventRow(event).get("last_error")).as("the original reason is kept").isEqualTo("UNKNOWN_REFERENCE: the payment carries no checkout or restaurant");
        Map<String, Object> e = onlyException(UNATTRIBUTED);
        assertThat(e.get("business_id")).as("no restaurant is ever inferred").isNull();
        assertThat(e).containsEntry("provider_event_id", event).containsEntry("payment_provider", PROVIDER).containsEntry("provider_payment_ref", ref)
                .containsEntry("amount_minor", ET_AMOUNT).containsEntry("currency", "ETB").containsEntry("involves_customer_funds", true).containsEntry("status", "OPEN");
        assertThat(((Timestamp) e.get("paid_at")).toInstant()).as("G3-J").isEqualTo(tPay);
        assertThat(e.get("payment_id")).as("no ledger row exists to link").isNull();
        assertThat(dueAfter(e)).as("G3-E: 24 elapsed hours").isEqualTo(Duration.ofHours(24));
        JsonNode f = facts(e);
        assertThat(f.path("fundsStatus").asString()).isEqualTo("COLLECTED_VERIFIED");
        assertThat(f.path("reasonCode").asString()).isEqualTo("NO_AGAFARI_REFERENCE");
        assertThat(f.path("rowCode").asString()).isEqualTo("UNKNOWN_REFERENCE");
        assertThat(f.path("source").asString()).isEqualTo("WEBHOOK");
        assertThat(f.path("eventId").asString()).startsWith("evt_");
        assertThat(f.toString()).as("no event body").doesNotContain("payment_ref");
        assertThat(audits("BILLING_EXCEPTION_OPENED", (String) e.get("id"))).isEqualTo(1);
        assertThat(lines("event=billing.exception_opened", Level.ERROR)).hasSize(1);
        assertThat(ledger()).as("no payment, invoice, period, subscription, refund or checkout change").isEqualTo(before);
    }

    @Test
    void aPaymentNamingAnUnknownCheckoutIsUnattributedAndTheMetadataRestaurantIsNotTrusted() {
        Map<String, String> m = new HashMap<>();
        m.put(PaymentMetadata.CHECKOUT_ID, UUID.randomUUID().toString());
        m.put(PaymentMetadata.BUSINESS_ID, owner.businessId());
        process(event(verified(micros(Instant.now()), m)));
        Map<String, Object> e = onlyException(UNATTRIBUTED);
        assertThat(e.get("business_id")).as("untrusted metadata").isNull();
        assertThat(facts(e).path("reasonCode").asString()).isEqualTo("UNKNOWN_CHECKOUT");
        assertThat(exceptions(owner)).isZero();
    }

    @Test
    void aPaymentWhoseCheckoutBelongsToAnotherRestaurantIsUnattributed() {
        Tenant other = data.tenant("g3-other");
        String c = checkout(owner, CheckoutKind.NEW, null);
        Map<String, String> m = new HashMap<>(metadata(c));
        m.put(PaymentMetadata.BUSINESS_ID, other.businessId());
        process(event(verified(micros(Instant.now()), m)));
        Map<String, Object> e = onlyException(UNATTRIBUTED);
        assertThat(e.get("business_id")).isNull();
        assertThat(facts(e).path("reasonCode").asString()).isEqualTo("OTHER_RESTAURANT");
        assertThat(e.get("checkout_id")).as("a platform row links no restaurant record").isNull();
        assertThat(checkoutStatus(c)).isEqualTo("OPEN");
    }

    @Test
    void aSessionMismatchOnAKnownCheckoutIsUnattributed() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        Map<String, String> m = new HashMap<>(metadata(c));
        m.put(PaymentMetadata.PROVIDER_SESSION_REF, "sess_someone_else");
        process(event(verified(micros(Instant.now()), m)));
        assertThat(facts(onlyException(UNATTRIBUTED)).path("reasonCode").asString()).isEqualTo("SESSION_MISMATCH");
        assertThat(current(owner)).as("never activated").isNull();
    }

    @Test
    void aRenewalPaymentOfAGenuinelyUnknownSubscriptionIsUnattributed() {
        String in = "in_g3_renew_" + SEQ.incrementAndGet();
        Instant start = micros(Instant.now().minus(1, ChronoUnit.DAYS));
        ProviderBilling b = ProviderBilling.renewal(in, "sub_g3_unknown_" + SEQ.get(), "cus_g3_" + SEQ.get(), "price_g3", PriceInterval.MONTH, start, oneMonth(start), "pi_" + in);
        fakeEt.payment(new VerifiedPayment(in, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), micros(Instant.now()), Map.of(), b));
        String event = event(in);
        assertThat(process(event)).isEqualTo(PaymentOutcomeService.Result.QUARANTINED);
        assertThat((String) eventRow(event).get("last_error")).contains(RenewalOutcome.UNKNOWN_SUBSCRIPTION);
        Map<String, Object> e = onlyException(UNATTRIBUTED);
        assertThat(facts(e).path("reasonCode").asString()).isEqualTo("UNKNOWN_SUBSCRIPTION");
        assertThat(facts(e).path("subscriptionRef").asString()).startsWith("sub_g3_unknown_");
        assertThat(facts(e).path("customerRef").asString()).startsWith("cus_g3_");
        assertThat(e).containsEntry("provider_payment_ref", in).containsEntry("amount_minor", ET_AMOUNT);
    }

    // ---- C-1: P-R4 unchanged ----

    @Test
    void aPaidRenewalCustomerMismatchKeepsP_R4AndGetsNoSecondException() {
        String n = SEQ.incrementAndGet() + "";
        String priceRef = "price_g3_" + n;
        price = UUID.randomUUID().toString(); // a provider price, as in RenewalFlowTest (R5-02 shape)
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, provider_price_ref, active, valid_from) "
                + "values (?, 'MODEL_4', 'ET', 'ETB', 'MONTH', ?, ?, ?, true, now() - interval '400 days')", price, ET_AMOUNT, 200 + SEQ.get(), priceRef);
        evaluator.setPeriodEndEnabled(false);
        String c = checkout(owner, CheckoutKind.NEW, null);
        Instant start = micros(clock.now().minus(1, ChronoUnit.DAYS)), end = oneMonth(start);
        ProviderBilling first = new ProviderBilling("in_g3_first_" + n, "sub_g3_" + n, "cus_g3_" + n, priceRef, PriceInterval.MONTH, start, end, "pi_first_" + n, null);
        fakeEt.payment(new VerifiedPayment("in_g3_first_" + n, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), micros(clock.now()), metadata(c), first));
        assertThat(process(event("in_g3_first_" + n))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        clock.setOffset(Duration.between(Instant.now(), end.plusSeconds(60)));
        ProviderBilling renewal = ProviderBilling.renewal("in_g3_renew_" + n, "sub_g3_" + n, "cus_other", priceRef, PriceInterval.MONTH, end, oneMonth(end), "pi_renew_" + n);
        fakeEt.payment(new VerifiedPayment("in_g3_renew_" + n, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), end, Map.of(), renewal));

        process(event("in_g3_renew_" + n));

        assertThat(jdbc.queryForList("select code from billing_exception where business_id = ?", String.class, owner.businessId()))
                .as("P-R4: the restaurant-level hold is unchanged").containsExactly(RenewalOutcome.CUSTOMER_MISMATCH);
        assertThat(count("select count(*) from billing_exception where code = ?", UNATTRIBUTED)).as("C-1: no second exception").isZero();
        assertThat(payments(owner)).as("P-R4 ledger treatment unchanged").isEqualTo(2);
    }

    // ---- C-2: only collected money ----

    @Test
    void failedPaymentsAndStateNotificationsAreNeverRepresentedAsCollectedMoney() {
        String failed = "pay_g3_failed_" + SEQ.incrementAndGet();
        fakeEt.payment(new VerifiedPayment(failed, ProviderPaymentStatus.FAILED, null, null, Map.of()));
        process(event(failed));
        String pending = "pay_g3_pending_" + SEQ.incrementAndGet();
        fakeEt.payment(new VerifiedPayment(pending, ProviderPaymentStatus.PENDING, null, null, Map.of()));
        assertThatThrownBy(() -> process(event(pending))).as("an unresolved payment is retried, not money");
        String in = "in_g3_failedinv_" + SEQ.incrementAndGet();
        fakeEt.invoiceState(new ProviderInvoiceState(in, ProviderInvoiceState.Status.OPEN, "subscription_cycle", "sub_g3_nobody_" + SEQ.get(), "cus_x", Instant.now(),
                Instant.now().plus(30, ChronoUnit.DAYS), true, false));
        String state = stateEvent("invoice.payment_failed", "INVOICE", in);
        assertThat(process(state)).isEqualTo(PaymentOutcomeService.Result.QUARANTINED);
        assertThat((String) eventRow(state).get("last_error")).as("a state notification of an unknown subscription is still quarantined").contains(RenewalOutcome.UNKNOWN_SUBSCRIPTION);

        assertThat(count("select count(*) from billing_exception")).as("C-2: none of these is collected money").isZero();
    }

    // ---- G3-G: one per verified payment ----

    @Test
    void oneExceptionPerPaymentAcrossTwoWebhooksAndReconciliationWithEveryEventTraceable() {
        String ref = verified(micros(Instant.now()), Map.of());
        String first = event(ref), second = event(ref);
        String internal = inbox.storeInternal(PROVIDER, ref);

        for (String e : List.of(first, second, internal)) assertThat(process(e)).isEqualTo(PaymentOutcomeService.Result.QUARANTINED);

        Map<String, Object> x = onlyException(UNATTRIBUTED);
        assertThat(x).containsEntry("provider_event_id", first);
        List<String> linked = jdbc.queryForList("select detail from admin_audit_event where action = 'BILLING_EXCEPTION_EVENT_LINKED' and target_id = ? order by occurred_at",
                String.class, x.get("id"));
        assertThat(linked).hasSize(2);
        assertThat(linked.get(0)).contains("providerEvent=" + second).contains("source=WEBHOOK");
        assertThat(linked.get(1)).contains("providerEvent=" + internal).contains("source=RECONCILIATION");
        for (String e : List.of(first, second, internal)) assertThat(eventRow(e).get("status")).isEqualTo("QUARANTINED");
    }

    @Test
    void concurrentDeliveriesOfOnePaymentCreateExactlyOneException() throws Exception {
        String ref = verified(micros(Instant.now()), Map.of());
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 4; i++) events.add(event(ref));
        events.add(inbox.storeInternal(PROVIDER, ref));
        ExecutorService pool = Executors.newFixedThreadPool(events.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<PaymentOutcomeService.Result>> runs = new ArrayList<>();
            for (String e : events) runs.add(pool.submit(() -> { go.await(); return process(e); }));
            go.countDown();
            for (Future<PaymentOutcomeService.Result> r : runs) assertThat(r.get(60, TimeUnit.SECONDS)).isEqualTo(PaymentOutcomeService.Result.QUARANTINED);
        } finally {
            pool.shutdownNow();
        }
        String id = (String) onlyException(UNATTRIBUTED).get("id");
        assertThat(audits("BILLING_EXCEPTION_EVENT_LINKED", id)).as("every other event is linked").isEqualTo(4);
    }

    // ---- atomic with the quarantine ----

    @Test
    void whenTheExceptionCantBeStoredTheQuarantineRollsBackAndTheEventStaysPending() {
        String ref = "pay_" + "x".repeat(300); // longer than provider_payment_ref: the exception insert fails
        fakeEt.payment(new VerifiedPayment(ref, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), micros(Instant.now()), Map.of()));
        String event = event(ref);

        assertThatThrownBy(() -> process(event)).as("the job fails and is retried by the normal rules");

        assertThat(eventRow(event).get("status")).as("no quarantine without its exception").isEqualTo("PENDING");
        assertThat(count("select count(*) from billing_exception")).isZero();
    }

    // ---- K-1: an unverified payment outcome ----

    @Test
    void aPaymentPathVerificationFailureGetsOneUnverifiedOutcomeReviewKeepingItsReason() {
        String ref = "pay_g3_vf_" + SEQ.incrementAndGet();
        fakeEt.payment(ref, new VerifiedPayment("pay_g3_someone_else", ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), micros(Instant.now()), Map.of()));
        String event = event(ref);

        assertThat(process(event)).isEqualTo(PaymentOutcomeService.Result.QUARANTINED);
        assertThat(process(event)).isEqualTo(PaymentOutcomeService.Result.ALREADY_DONE);

        assertThat((String) eventRow(event).get("last_error")).isEqualTo("VERIFICATION_FAILED: the re-fetch named another payment");
        Map<String, Object> e = onlyException(PENDING_REVIEW);
        assertThat(e.get("business_id")).isNull();
        assertThat(e).containsEntry("provider_event_id", event).containsEntry("involves_customer_funds", false);
        assertThat(e.get("amount_minor")).as("no amount: nothing is confirmed collected").isNull();
        assertThat(e.get("provider_payment_ref")).as("no verified payment identity").isNull();
        assertThat(dueAfter(e)).isEqualTo(Duration.ofHours(24));
        JsonNode f = facts(e);
        assertThat(f.path("fundsStatus").asString()).isEqualTo("UNVERIFIED");
        assertThat(f.path("quarantineReason").asString()).isEqualTo("VERIFICATION_FAILED: the re-fetch named another payment");
        assertThat(f.path("requestedPaymentRef").asString()).isEqualTo(ref);
        assertThat(count("select count(*) from billing_exception")).as("one per event").isEqualTo(1);
        assertThat(count("select count(*) from payment")).isZero();
    }

    // ---- reconciliation (G3-I) ----

    @Test
    void aPaymentFoundOnlyByReconciliationIsOwnedButNeverReplayedAutomatically() {
        String ref = verified(micros(Instant.now()), Map.of());
        String internal = inbox.storeInternal(PROVIDER, ref);
        process(internal);
        Map<String, Object> e = onlyException(UNATTRIBUTED);
        assertThat(facts(e).path("source").asString()).isEqualTo("RECONCILIATION");
        int fetches = fakeEt.verifyCalls();
        assertThat(process(internal)).isEqualTo(PaymentOutcomeService.Result.ALREADY_DONE);
        assertThat(fakeEt.verifyCalls()).as("G5").isEqualTo(fetches);
    }

    // ---- deadlines: 24 h overdue, 48 h status-only escalation ----

    @Test
    void itIsOverdueAt24HoursAndEscalatedOnceAt48HoursWithNoTargetAssigned() {
        process(event(verified(micros(Instant.now()), Map.of())));
        Map<String, Object> e = onlyException(UNATTRIBUTED);
        String id = (String) e.get("id");
        Instant created = ((Timestamp) e.get("created_at")).toInstant();

        sla.sweep(created.plus(Duration.ofHours(24)).plusSeconds(60));
        assertThat(audits("BILLING_EXCEPTION_OVERDUE", id)).isEqualTo(1);
        assertThat(status(id)).as("not escalated before 48 h").isEqualTo("OPEN");

        sla.sweep(created.plus(Duration.ofHours(47)));
        assertThat(status(id)).isEqualTo("OPEN");

        sla.sweep(created.plus(Duration.ofHours(48)).plusSeconds(60));
        Map<String, Object> after = jdbc.queryForMap("select * from billing_exception where id = ?", id);
        assertThat(after).containsEntry("status", "ESCALATED");
        assertThat(after.get("escalated_to")).as("G3-A open: status only, nobody assigned").isNull();
        assertThat((String) after.get("escalation_reason")).contains("48 elapsed hours");
        assertThat(audits("BILLING_EXCEPTION_ESCALATED", id)).isEqualTo(1);
        assertThat(lines("event=billing.exception_escalated", Level.ERROR)).hasSize(1);

        sla.sweep(created.plus(Duration.ofHours(72)));
        assertThat(audits("BILLING_EXCEPTION_ESCALATED", id)).as("once").isEqualTo(1);
    }

    @Test
    void concurrentHourlySweepsEscalateOnceWithOneAuditAndOneAlert() throws Exception {
        process(event(verified(micros(Instant.now()), Map.of())));
        Map<String, Object> e = onlyException(UNATTRIBUTED);
        String id = (String) e.get("id");
        int versionBefore = version(id);
        Instant at = ((Timestamp) e.get("created_at")).toInstant().plus(Duration.ofHours(48)).plusSeconds(60);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> sweeps = new ArrayList<>();
            for (int i = 0; i < 4; i++) sweeps.add(pool.submit(() -> { go.await(); return sla.sweep(at); }));
            go.countDown();
            for (Future<Integer> f : sweeps) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        for (int i = 1; i <= 3; i++) sla.sweep(at.plus(Duration.ofHours(i))); // later hourly sweeps
        assertThat(status(id)).isEqualTo("ESCALATED");
        assertThat(version(id)).as("exactly one escalation transition").isEqualTo(versionBefore + 1);
        assertThat(audits("BILLING_EXCEPTION_ESCALATED", id)).as("exactly one escalation audit").isEqualTo(1);
        assertThat(lines("event=billing.exception_escalated", Level.ERROR)).as("no duplicate alert").hasSize(1);
    }

    @Test
    void the48HourEscalationLeavesResolvedRowsAloneAndAlsoCoversUnverifiedOutcomes() throws Exception {
        process(event(verified(micros(Instant.now()), Map.of())));
        String resolved = (String) onlyException(UNATTRIBUTED).get("id");
        assertThat(act(resolved, "resolve", "{\"rowVersion\":" + version(resolved) + ",\"resolution\":\"NO_ACTION_REQUIRED\",\"note\":\"Attributed by replay\"}").getStatus()).isEqualTo(200);
        String ref = "pay_g3_vf2_" + SEQ.incrementAndGet();
        fakeEt.payment(ref, new VerifiedPayment("pay_g3_other2", ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), micros(Instant.now()), Map.of()));
        process(event(ref));
        String review = (String) onlyException(PENDING_REVIEW).get("id");

        sla.sweep(Instant.now().plus(Duration.ofHours(49)));

        assertThat(status(resolved)).isEqualTo("RESOLVED");
        assertThat(status(review)).as("AGF D-3 (register §107 D3-A): a restaurant-less unverified outcome escalates too").isEqualTo("ESCALATED");
    }

    // ---- G3-C: attribution only by replaying Stripe evidence ----

    @Test
    void replayAttributesOnlyWhenTheProviderEvidenceDoesAndNeverTwice() throws Exception {
        String c = checkout(owner, CheckoutKind.NEW, null);
        Instant tPay = micros(Instant.now());
        String ref = verified(tPay, Map.of());
        process(event(ref));
        String id = (String) onlyException(UNATTRIBUTED).get("id");

        assertThat(act(id, "replay", "{\"rowVersion\":" + version(id) + "}").getStatus()).isEqualTo(200);
        assertThat(current(owner)).as("no new evidence: nothing applied").isNull();

        fakeEt.payment(new VerifiedPayment(ref, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), tPay, metadata(c)));
        assertThat(act(id, "replay", "{\"rowVersion\":" + version(id) + "}").getStatus()).isEqualTo(200);
        assertThat(act(id, "replay", "{\"rowVersion\":" + version(id) + "}").getStatus()).isEqualTo(200);
        assertThat(payments(owner)).as("applied once, by Stripe evidence").isEqualTo(1);
        assertThat(current(owner)).isNotNull();
        assertThat(status(id)).as("left for the admin to resolve").isNotEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("select business_id from billing_exception where id = ?", String.class, id)).as("the exception is never re-pointed").isNull();
        assertThat(audits("BILLING_EXCEPTION_EVENT_LINKED", id)).as("a replay of the exception's own event links nothing").isZero();
    }

    @Test
    void noRestaurantOrPaymentIdentityCanBeChangedAfterCreation() {
        process(event(verified(micros(Instant.now()), Map.of())));
        String id = (String) onlyException(UNATTRIBUTED).get("id");
        assertThatThrownBy(() -> jdbc.update("update billing_exception set business_id = ? where id = ?", owner.businessId(), id)).hasMessageContaining("can't be changed");
        assertThatThrownBy(() -> jdbc.update("update billing_exception set provider_payment_ref = 'pay_changed' where id = ?", id)).hasMessageContaining("can't be changed");
        assertThatThrownBy(() -> jdbc.update("update billing_exception set payment_provider = 'FAKE_US' where id = ?", id)).hasMessageContaining("can't be changed");
    }

    // ---- G3-D: funds handled externally needs the Stripe refund reference ----

    @Test
    void resolvingAsHandledExternallyNeedsTheStripeRefundReferenceInTheAuditedNote() throws Exception {
        process(event(verified(micros(Instant.now()), Map.of())));
        String id = (String) onlyException(UNATTRIBUTED).get("id");

        MockHttpServletResponse refused = act(id, "resolve", "{\"rowVersion\":" + version(id) + ",\"resolution\":\"FUNDS_HANDLED_EXTERNALLY\",\"note\":\"Refunded in Stripe\"}");
        assertThat(refused.getStatus()).isEqualTo(400);
        assertThat(refused.getContentAsString()).contains("note");
        assertThat(status(id)).isEqualTo("OPEN");

        MockHttpServletResponse ok = act(id, "resolve", "{\"rowVersion\":" + version(id) + ",\"resolution\":\"FUNDS_HANDLED_EXTERNALLY\",\"note\":\"Refunded in Stripe: re_3SyntheticRefund01\"}");
        assertThat(ok.getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select detail from admin_audit_event where action = 'BILLING_EXCEPTION_RESOLVED' and target_id = ?", String.class, id))
                .contains("stripeRefund=re_3SyntheticRefund01");
        assertThat(count("select count(*) from refund_request")).as("no Agafari refund").isZero();
    }

    @Test
    void theStripeRefundReferenceRuleAppliesOnlyToUnattributedPayments() throws Exception {
        String ref = "pay_g3_vf3_" + SEQ.incrementAndGet();
        fakeEt.payment(ref, new VerifiedPayment("pay_g3_other3", ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), micros(Instant.now()), Map.of()));
        process(event(ref));
        String id = (String) onlyException(PENDING_REVIEW).get("id");
        assertThat(act(id, "resolve", "{\"rowVersion\":" + version(id) + ",\"resolution\":\"FUNDS_HANDLED_EXTERNALLY\",\"note\":\"Checked with the provider\"}").getStatus())
                .isEqualTo(200);
    }

    // ---- M-1: the migration ----

    @Test
    void theMigrationIsAdditiveAndExistingInsertsStillWork() {
        List<Map<String, Object>> cols = jdbc.queryForList("select column_name, is_nullable from information_schema.columns where table_name = 'billing_exception' "
                + "and column_name in ('payment_provider', 'provider_payment_ref') order by column_name");
        assertThat(cols).hasSize(2).allSatisfy(c -> assertThat(c.get("is_nullable")).isEqualTo("YES"));
        String event = event(verified(micros(Instant.now()), Map.of()));
        jdbc.update("insert into billing_exception (id, business_id, code, provider_event_id, involves_customer_funds, facts, status, created_at) "
                + "values (?, null, 'UNATTRIBUTED_PAYMENT', ?, false, '{}'::jsonb, 'OPEN', now())", UUID.randomUUID().toString(), event);
        assertThat(count("select count(*) from billing_exception where payment_provider is null and provider_payment_ref is null")).as("an insert without the new columns").isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("insert into billing_exception (id, business_id, code, provider_event_id, involves_customer_funds, facts, status, created_at, payment_provider) "
                + "values (?, null, 'UNATTRIBUTED_PAYMENT', ?, false, '{}'::jsonb, 'OPEN', now(), 'FAKE_ET')", UUID.randomUUID().toString(), event(verified(micros(Instant.now()), Map.of()))))
                .as("both identity columns or neither").hasMessageContaining("ck_billing_exception_payment_identity");
    }

    @Test
    void theDatabaseRefusesASecondExceptionForTheSamePaymentAndCode() {
        String ref = verified(micros(Instant.now()), Map.of());
        String a = event(ref), b = event(ref);
        String sql = "insert into billing_exception (id, business_id, code, provider_event_id, involves_customer_funds, facts, status, created_at, payment_provider, provider_payment_ref) "
                + "values (?, null, 'UNATTRIBUTED_PAYMENT', ?, true, '{}'::jsonb, 'OPEN', now(), ?, ?)";
        jdbc.update(sql, UUID.randomUUID().toString(), a, PROVIDER, ref);
        assertThatThrownBy(() -> jdbc.update(sql, UUID.randomUUID().toString(), b, PROVIDER, ref)).hasMessageContaining("uk_billing_exception_provider_payment");
    }

    @Test
    void theMigrationRunsInOneTransactionSoAFailureLeavesNoPartialSchema() throws Exception {
        String script = Files.readString(Path.of("src/main/resources/db/migration/V23_7__unattributed_payment_identity.sql"), StandardCharsets.UTF_8);
        assertThat(script.toLowerCase()).as("Flyway's per-migration transaction is kept").doesNotContain("executeintransaction=false").doesNotContain("concurrently")
                .doesNotContain("commit");
        // PostgreSQL DDL is transactional: the same statements, failing at the end, leave nothing behind
        jdbc.execute("create table g3_ddl_probe (id varchar(36) primary key)");
        try {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> {
                jdbc.execute("alter table g3_ddl_probe add column payment_provider varchar(32)");
                jdbc.execute("alter table g3_ddl_probe add column provider_payment_ref varchar(255)");
                jdbc.execute("create unique index uk_g3_ddl_probe on g3_ddl_probe (payment_provider, provider_payment_ref)");
                jdbc.execute("alter table g3_ddl_probe add constraint ck_fails check (no_such_column is null)");
            }));
            assertThat(count("select count(*) from information_schema.columns where table_name = 'g3_ddl_probe'")).as("only the original column").isEqualTo(1);
            assertThat(count("select count(*) from pg_indexes where indexname = 'uk_g3_ddl_probe'")).isZero();
        } finally {
            jdbc.execute("drop table g3_ddl_probe");
        }
    }

    // ---- fixtures ----

    /** A verified successful payment of {@code ET_AMOUNT} with the given metadata; returns its reference. */
    private String verified(Instant tPay, Map<String, String> metadata) {
        String ref = "pay_g3_" + SEQ.incrementAndGet();
        fakeEt.payment(new VerifiedPayment(ref, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), tPay, metadata));
        return ref;
    }

    private String stateEvent(String type, String kind, String ref) {
        String eventId = "evt_state_g3_" + SEQ.incrementAndGet();
        byte[] body = ("{\"id\":\"" + eventId + "\",\"type\":\"" + type + "\",\"state_kind\":\"" + kind + "\",\"state_ref\":\"" + ref + "\"}").getBytes(StandardCharsets.UTF_8);
        inbox.store(PROVIDER, new InboundEvent(eventId, type), body);
        return jdbc.queryForObject("select id from provider_event where provider = ? and event_id = ?", String.class, PROVIDER, eventId);
    }

    private Map<String, Object> onlyException(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from billing_exception where code = ?", code);
        assertThat(rows).as("exactly one " + code).hasSize(1);
        return rows.get(0);
    }

    private JsonNode facts(Map<String, Object> e) {
        return mapper.readTree(String.valueOf(e.get("facts")));
    }

    private static Duration dueAfter(Map<String, Object> e) {
        return Duration.between(((Timestamp) e.get("created_at")).toInstant(), ((Timestamp) e.get("due_at")).toInstant());
    }

    private String status(String id) {
        return jdbc.queryForObject("select status from billing_exception where id = ?", String.class, id);
    }

    private int version(String id) {
        return jdbc.queryForObject("select row_version from billing_exception where id = ?", Integer.class, id);
    }

    private int audits(String action, String target) {
        return count("select count(*) from admin_audit_event where action = ? and target_id = ?", action, target);
    }

    private MockHttpServletResponse act(String id, String action, String body) throws Exception {
        return mockMvc.perform(post("/api/admin/billing/exceptions/" + id + "/" + action).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", data.adminBearer(admin))).andReturn().getResponse();
    }

    private List<String> lines(String prefix, Level level) {
        return logs.list.stream().filter(l -> l.getFormattedMessage().startsWith(prefix) && l.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

    private record LedgerRows(List<Map<String, Object>> rows) {
    }

    private LedgerRows ledger() {
        return new LedgerRows(jdbc.queryForList("""
                select 'payment' as t, status as s, count(*) as n from payment group by status
                union all select 'invoice', status, count(*) from billing_invoice group by status
                union all select 'period', '', count(*) from subscription_period
                union all select 'subscription', status, count(*) from business_subscription group by status
                union all select 'refund', status, count(*) from refund_request group by status
                union all select 'checkout', status, count(*) from checkout_session group by status
                order by 1, 2"""));
    }
}
