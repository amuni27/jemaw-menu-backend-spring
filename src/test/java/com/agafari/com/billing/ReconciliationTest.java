package com.agafari.com.billing;

import com.agafari.com.billing.fake.FakeProviderClient;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.ProviderRecord;
import com.agafari.com.billing.port.RecordType;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.billing.webhook.InboxService;
import com.agafari.com.billing.webhook.WebhookController;
import com.agafari.com.jobs.JobHandler;
import com.agafari.com.jobs.JobQueue;
import com.agafari.com.support.MonetizationFixtures;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF SUB-R4-14, reduced scope (PO G1–G5): checkout recovery and expiry, payment listing repaired through the approved
 * outcome path, and the locally verifiable ledger invariants, on PostgreSQL. Provider renewal and invoice or subscription
 * comparison are BLOCKED (G2) and not tested here.
 */
class ReconciliationTest extends PaymentOutcomeTestBase {

    @Autowired private ReconciliationService reconciliation;
    @Autowired private PaymentReview review;
    @Autowired private WebhookController webhooks;
    @Autowired private JobQueue queue;
    @Autowired private ApplicationContext context;

    @BeforeEach
    void enableWebhooks() {
        webhooks.setBillingEnabled(Market.ET, true);
    }

    @AfterEach
    void disableWebhooks() {
        webhooks.setBillingEnabled(Market.ET, false);
        clock.reset();
    }

    private Instant soon() {
        return micros(Instant.now().plus(1, ChronoUnit.MINUTES));
    }

    private void listed(String ref, Instant at) {
        fakeEt.record(new ProviderRecord(ref, RecordType.PAYMENT, at));
    }

    /** One daily run, then the inbox jobs it queued, run as the job runner would. */
    private ReconciliationService.DailyResult daily() {
        ReconciliationService.DailyResult r = reconciliation.daily(clock.now());
        for (String id : jdbc.queryForList("select id from provider_event where event_id like ? and status = 'PENDING' order by received_at", String.class,
                InboxService.INTERNAL_PREFIX + "%")) {
            try {
                outcomes.process(id);
            } catch (RuntimeException retriedLater) {
                // transient outcomes stay PENDING, as with the job runner
            }
        }
        return r;
    }

    private int reconciledAudits() {
        return count("select count(*) from admin_audit_event where action = 'BILLING_RECONCILED'");
    }

    private String exceptionCode(Tenant t) {
        return jdbc.queryForObject("select code from billing_exception where business_id = ?", String.class, t.businessId());
    }

    // ---- AC1: a lost webhook ----

    @Test
    void ac1APaymentWhoseWebhookWasLostIsRepairedThroughTheNormalPath() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        String ref = paid(c, soon());
        listed(ref, soon());

        ReconciliationService.DailyResult r = daily();

        assertThat(r.eventsCreated()).isEqualTo(1);
        assertThat(providerSubscriptions(owner)).isEqualTo(1);
        assertThat(periods(owner)).isEqualTo(1);
        Map<String, Object> pay = jdbc.queryForMap("select * from payment where provider_payment_ref = ?", ref);
        assertThat(pay.get("verification")).as("G4: reconciliation evidence").isEqualTo("RECONCILIATION_API_FETCH");
        Map<String, Object> event = jdbc.queryForMap("select event_id, type, status from provider_event where event_id = ?", InboxService.INTERNAL_PREFIX + ref);
        assertThat(event).containsEntry("type", InboxService.INTERNAL_TYPE).containsEntry("status", "PROCESSED");
        assertThat(fakeEt.verifyCalls()).as("a fresh re-fetch, the listing is never evidence").isPositive();
        assertThat(count("select count(*) from admin_audit_event where action = 'BILLING_RECONCILED' and target_id = ?", ref)).isEqualTo(1);
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void aPaymentAlreadyRecordedByItsWebhookIsNotRecordedAgainAndKeepsItsEvidence() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        String ref = paid(c, soon());
        process(event(ref));
        listed(ref, soon());

        daily();

        assertThat(payments(owner)).isEqualTo(1);
        assertThat(periods(owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select verification from payment where provider_payment_ref = ?", String.class, ref)).isEqualTo("SIGNED_WEBHOOK+API_FETCH");
        assertThat(exceptions(owner)).isZero();
    }

    @Test
    void aListedPaymentWithAWrongAmountIsStillJudgedOnlyByTheReFetch() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        String ref = paid(c, soon(), new Money(ET_AMOUNT - 1, Currency.ETB), metadata(c));
        listed(ref, soon());

        daily();

        assertThat(exceptionCode(owner)).isEqualTo(PaymentOutcome.VERIFICATION_FAILED);
        assertThat(current(owner)).isNull();
    }

    // ---- AC4: unknown provider payments ----

    @Test
    void ac4AnAttributableUnknownPaymentGetsARestaurantScopedExceptionFromVerifiedOwnership() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        MonetizationFixtures.subscription(jdbc, owner.businessId(), "MODEL_4", 1, admin.getId()); // row 6
        String ref = paid(c, soon());
        listed(ref, soon());

        daily();

        Map<String, Object> x = jdbc.queryForMap("select * from billing_exception where business_id = ?", owner.businessId());
        assertThat(x.get("code")).isEqualTo(PaymentOutcome.STATE_CHANGED);
        assertThat(x.get("provider_event_id")).isEqualTo(jdbc.queryForObject("select id from provider_event where event_id = ?", String.class, InboxService.INTERNAL_PREFIX + ref));
        assertThat(jdbc.queryForObject("select verification from payment where provider_payment_ref = ?", String.class, ref)).isEqualTo("RECONCILIATION_API_FETCH");
    }

    @Test
    void ac4AnUnattributableUnknownPaymentStaysQuarantinedWithoutAnExceptionAndIsNeverReplayed() {
        String ref = "pay_unattributable";
        fakeEt.payment(new VerifiedPayment(ref, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), soon(), Map.of()));
        listed(ref, soon());

        daily();

        Map<String, Object> event = jdbc.queryForMap("select id, status, attempts, last_error from provider_event where event_id = ?", InboxService.INTERNAL_PREFIX + ref);
        assertThat(event.get("status")).isEqualTo("QUARANTINED");
        assertThat((String) event.get("last_error")).isNotBlank();
        assertThat(count("select count(*) from billing_exception")).as("R-A: no restaurant is inferred").isZero();
        assertThat(count("select count(*) from payment")).isZero();
        int fetches = fakeEt.verifyCalls();

        daily();

        assertThat(jdbc.queryForMap("select status, attempts from provider_event where id = ?", event.get("id"))).as("G5: never replayed automatically")
                .containsEntry("status", "QUARANTINED").containsEntry("attempts", event.get("attempts"));
        assertThat(fakeEt.verifyCalls()).isEqualTo(fetches);
    }

    // ---- G4: internal events are distinguishable ----

    @Test
    void aSignedWebhookCantUseTheReservedReconciliationIdentity() throws Exception {
        for (String body : List.of("{\"id\":\"" + InboxService.INTERNAL_PREFIX + "pay_x\",\"type\":\"payment.succeeded\",\"payment_ref\":\"pay_x\"}",
                "{\"id\":\"evt_ok\",\"type\":\"" + InboxService.INTERNAL_TYPE + "\",\"payment_ref\":\"pay_x\"}")) {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            int status = mockMvc.perform(post("/api/billing/webhooks/" + PROVIDER).contentType(MediaType.APPLICATION_JSON).content(b)
                    .header(FakeProviderClient.SIGNATURE_HEADER, FakeProviderClient.sign(b))).andReturn().getResponse().getStatus();
            assertThat(status).isEqualTo(400);
        }
        assertThat(count("select count(*) from provider_event")).isZero();
    }

    @Test
    void anInternalEventIsIdempotentPerProviderAndPayment() {
        String first = inbox.storeInternal(PROVIDER, "pay_same");
        String again = inbox.storeInternal(PROVIDER, "pay_same");
        String otherProvider = inbox.storeInternal("FAKE_US", "pay_same");

        assertThat(again).isEqualTo(first);
        assertThat(otherProvider).isNotEqualTo(first);
        assertThat(count("select count(*) from provider_event where event_id = ?", InboxService.INTERNAL_PREFIX + "pay_same")).isEqualTo(2);
        assertThat(count("select count(*) from job where type = 'inbox.process'")).isEqualTo(2);
    }

    // ---- AC2: invariants ----

    @Test
    void ac2AVerifiedChargeWithNoOutcomeIsFlaggedAsCustomerFunds() throws Exception {
        String ref = seedUnaccountedCharge(owner);

        daily();

        Map<String, Object> x = jdbc.queryForMap("select * from billing_exception where business_id = ?", owner.businessId());
        assertThat(x.get("code")).isEqualTo(ReconciliationService.CHARGE_UNACCOUNTED);
        assertThat(x.get("involves_customer_funds")).isEqualTo(true);
        assertThat(x.get("status")).isEqualTo("OPEN");
        assertThat(x.get("payment_id")).isEqualTo(jdbc.queryForObject("select id from payment where provider_payment_ref = ?", String.class, ref));
        assertThat(x.get("due_at")).as("the SLA applies").isNotNull();
        assertThat(x.get("facts").toString()).contains("unaccounted");
        assertThat(review.underReview(owner.businessId())).isTrue();
        assertThat(httpStatusWithConflictCode(owner, "k-unaccounted", price, "PAYMENT_UNDER_REVIEW")).as("R-2 applies").isEqualTo(409);
    }

    @Test
    void ac2APeriodWhoseInvoiceIsNoLongerPaidIsFlaggedWithoutCustomerFunds() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        process(event(paid(c, soon())));
        jdbc.update("update billing_invoice set status = 'VOID' where business_id = ?", owner.businessId());

        daily();

        Map<String, Object> x = jdbc.queryForMap("select * from billing_exception where business_id = ?", owner.businessId());
        assertThat(x.get("code")).isEqualTo(ReconciliationService.PERIOD_WITHOUT_PAID_INVOICE);
        assertThat(x.get("involves_customer_funds")).isEqualTo(false);
        assertThat(x.get("due_at")).as("no customer-funds SLA").isNull();
        assertThat(x.get("facts").toString()).contains("VOID");
        assertThat(review.underReview(owner.businessId())).isFalse();
        assertThat(current(owner)).as("no lifecycle change").isNotNull();
    }

    @Test
    void ac2ALedgerRowTheProviderContradictsIsFlaggedWithItsEvidence() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        String ref = paid(c, soon());
        process(event(ref));
        fakeEt.payment(new VerifiedPayment(ref, ProviderPaymentStatus.FAILED, new Money(ET_AMOUNT, Currency.ETB), null, metadata(c)));
        Map<String, Object> before = jdbc.queryForMap("select * from payment where provider_payment_ref = ?", ref);

        daily();

        Map<String, Object> x = jdbc.queryForMap("select * from billing_exception where business_id = ?", owner.businessId());
        assertThat(x.get("code")).isEqualTo(ReconciliationService.LEDGER_UNCONFIRMED);
        assertThat(x.get("involves_customer_funds")).isEqualTo(false);
        assertThat(x.get("facts").toString()).contains("FAILED");
        assertThat(jdbc.queryForMap("select * from payment where provider_payment_ref = ?", ref)).as("the ledger is never changed").isEqualTo(before);
        assertThat(current(owner)).as("no lifecycle change").isNotNull();
    }

    @Test
    void g1AProviderTimeoutOrPendingAnswerIsNotEvidenceOfMissingMoney() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        String ref = paid(c, soon());
        process(event(ref));
        fakeEt.script(FakeProviderClient.Script.VERIFY_TIMEOUT);

        ReconciliationService.DailyResult r = daily();

        assertThat(r.unverifiable()).isEqualTo(1);
        assertThat(exceptions(owner)).isZero();
        assertThat(count("select count(*) from admin_audit_event where action = 'BILLING_RECONCILED' and detail like 'unverifiable%'")).isEqualTo(1);

        fakeEt.script(FakeProviderClient.Script.DELAY).delayedFetches(100);
        assertThat(daily().unverifiable()).isEqualTo(1);
        assertThat(exceptions(owner)).isZero();
    }

    @Test
    void aConsumedChargeAndARefundOrExceptionLinkedChargeAreAccountedFor() {
        String c = checkout(owner, CheckoutKind.NEW, null);
        process(event(paid(c, soon())));
        process(event(paid(c, soon().plusSeconds(1)))); // row 2: a refund request and its exception

        daily();

        assertThat(count("select count(*) from billing_exception where code like 'RECONCILIATION_%'")).isZero();
    }

    // ---- AC3 ----

    @Test
    void ac3RunningTwiceHasNoExtraEffect() throws Exception {
        String c = checkout(owner, CheckoutKind.NEW, null);
        listed(paid(c, soon()), soon());
        seedUnaccountedCharge(data.tenant("recon-twice"));
        reconciliation.hourly(clock.now());
        daily();
        Map<String, Integer> before = snapshot();

        reconciliation.hourly(clock.now());
        ReconciliationService.DailyResult second = daily();

        assertThat(second.eventsCreated()).isZero();
        assertThat(second.exceptionsCreated()).isZero();
        assertThat(snapshot()).isEqualTo(before);
    }

    private Map<String, Integer> snapshot() {
        return Map.of("events", count("select count(*) from provider_event"), "payments", count("select count(*) from payment"), "periods", count("select count(*) from subscription_period"),
                "exceptions", count("select count(*) from billing_exception"), "refunds", count("select count(*) from refund_request"), "audits", reconciledAudits(),
                "jobs", count("select count(*) from job"), "subs", count("select count(*) from business_subscription"));
    }

    // ---- concurrency: reconciliation vs the webhook ----

    @Test
    void reconciliationRacingTheWebhookForOnePaymentRecordsAndConsumesItOnce() throws Exception {
        for (int round = 0; round < 6; round++) {
            Tenant t = data.tenant("recon-race-" + round);
            String ref = paid(checkout(t, CheckoutKind.NEW, null), soon());
            String webhook = event(ref);
            String internal = inbox.storeInternal(PROVIDER, ref);

            race(2, i -> () -> {
                process(i == 0 ? webhook : internal);
                return 0;
            });

            assertThat(payments(t)).isEqualTo(1);
            assertThat(periods(t)).isEqualTo(1);
            assertThat(exceptions(t)).isZero();
            assertThat(jdbc.queryForObject("select status from provider_event where id = ?", String.class, webhook)).isEqualTo("PROCESSED");
            assertThat(jdbc.queryForObject("select status from provider_event where id = ?", String.class, internal)).isEqualTo("PROCESSED");
        }
        assertEveryChargeAccountedForOnce();
    }

    // ---- hourly: checkouts ----

    @Test
    void aStaleCreatingCheckoutIsRecoveredEvenWhenItsJobIsDead() {
        String id = creating(owner, 15);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(s -> queue.enqueue(CheckoutCreateHandler.TYPE, id, new CheckoutCreatePayload(id)));
        jdbc.update("update job set status = 'DEAD', finished_at = now() where type = ? and key = ?", CheckoutCreateHandler.TYPE, id);

        ReconciliationService.HourlyResult r = reconciliation.hourly(clock.now());

        assertThat(r.checkoutsRecovered()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("select status, provider_session_ref from checkout_session where id = ?", id);
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat((String) row.get("provider_session_ref")).startsWith("fake_session_");
        assertThat(fakeEt.createCalls()).isEqualTo(1);
        assertThat(count("select count(*) from admin_audit_event where action = 'BILLING_RECONCILED' and target_id = ?", id)).isEqualTo(1);
    }

    @Test
    void aCreatingCheckoutUnderTenMinutesOldIsLeftToItsOwnJob() {
        String id = creating(owner, 5);

        assertThat(reconciliation.hourly(clock.now()).checkoutsRecovered()).isZero();

        assertThat(jdbc.queryForObject("select status from checkout_session where id = ?", String.class, id)).isEqualTo("CREATING");
        assertThat(fakeEt.createCalls()).isZero();
    }

    @Test
    void g3ACreatingCheckoutIsNeverOpenedWhileAPaymentIsUnderReview() {
        String id = creating(owner, 15);
        seedOpenFundsException(owner);

        ReconciliationService.HourlyResult r = reconciliation.hourly(clock.now());

        assertThat(r.checkoutsRefusedUnderReview()).isEqualTo(1);
        assertThat(jdbc.queryForMap("select status, cancelled_reason, provider_session_ref from checkout_session where id = ?", id))
                .containsEntry("status", "CREATE_FAILED").containsEntry("cancelled_reason", "PAYMENT_UNDER_REVIEW").containsEntry("provider_session_ref", null);
        assertThat(fakeEt.createCalls()).as("no provider call").isZero();
    }

    @Test
    void g3TheCreateBackstopJobAlsoRefusesDuringAReview() {
        String id = creating(owner, 1);
        seedOpenFundsException(owner);

        context.getBean(CheckoutCreateHandler.class).handle(new CheckoutCreatePayload(id));

        assertThat(jdbc.queryForObject("select cancelled_reason from checkout_session where id = ?", String.class, id)).isEqualTo("PAYMENT_UNDER_REVIEW");
        assertThat(fakeEt.createCalls()).isZero();
    }

    @Test
    void anOpenCheckoutUnderReviewIsLeftAsItIs() {
        String id = checkout(owner, CheckoutKind.NEW, null);
        seedOpenFundsException(owner);

        reconciliation.hourly(clock.now());

        assertThat(jdbc.queryForObject("select status from checkout_session where id = ?", String.class, id)).as("G3: no new policy for OPEN checkouts").isEqualTo("OPEN");
    }

    @Test
    void expiredOpenAndCancelRequestedCheckoutsAreClosedAndUnexpiredOnesKept() {
        String open = checkout(owner, CheckoutKind.NEW, null);
        Tenant t2 = data.tenant("recon-cancel");
        String cancelled = checkout(t2, CheckoutKind.NEW, null);
        checkouts.cancel(t2.businessId(), cancelled);
        Tenant t3 = data.tenant("recon-fresh");
        String fresh = checkout(t3, CheckoutKind.NEW, null, price, ET_AMOUNT, Instant.now().plus(Duration.ofHours(23)));
        jdbc.update("update checkout_session set expires_at = now() - interval '1 minute' where id in (?, ?)", open, cancelled);

        ReconciliationService.HourlyResult r = reconciliation.hourly(clock.now());

        assertThat(r.checkoutsExpired()).isEqualTo(2);
        assertThat(checkoutStatus(open)).isEqualTo("EXPIRED");
        assertThat(checkoutStatus(cancelled)).isEqualTo("CANCELLED");
        assertThat(checkoutStatus(fresh)).isEqualTo("OPEN");
    }

    @Test
    void aPaymentFoundAfterItsCheckoutExpiredIsJudgedByItsPaymentTime() {
        Instant expires = micros(Instant.now().plus(Duration.ofHours(1)));
        String early = checkout(owner, CheckoutKind.NEW, null, price, ET_AMOUNT, expires);
        String onTime = paid(early, expires.minusSeconds(60));
        Tenant late = data.tenant("recon-late");
        String lateCheckout = checkout(late, CheckoutKind.NEW, null, price, ET_AMOUNT, expires);
        String tooLate = paid(lateCheckout, expires.plusSeconds(60));
        listed(onTime, expires);
        listed(tooLate, expires);
        clock.setOffset(Duration.ofHours(2));

        reconciliation.hourly(clock.now());
        assertThat(checkoutStatus(early)).isEqualTo("EXPIRED");
        daily();

        assertThat(providerSubscriptions(owner)).as("paid before expiry: honoured").isEqualTo(1);
        assertThat(providerSubscriptions(late)).as("paid after expiry: no activation").isZero();
        assertThat(exceptionCode(late)).isEqualTo(PaymentOutcome.LATE_PAYMENT);
        assertThat(jdbc.queryForObject("select r.status from refund_request r join payment p on p.id = r.payment_id where p.business_id = ?", String.class, late.businessId()))
                .as("requested only; reconciliation submits and settles nothing").isEqualTo("REQUESTED");
        assertThat(fakeEt.refundSubmitCalls()).isZero();
    }

    // ---- safety ----

    @Test
    void reconciliationNeverSettlesARefundOrTouchesAnUnknownOne() {
        Instant expires = micros(Instant.now().plus(Duration.ofHours(1)));
        process(event(paid(checkout(owner, CheckoutKind.NEW, null, price, ET_AMOUNT, expires), expires.plusSeconds(1)))); // row 3
        String refund = jdbc.queryForObject("select id from refund_request", String.class);
        jdbc.update("update refund_request set status = 'SUBMITTING', idempotency_key = ?, submitted_at = now() where id = ?", "refund:" + refund, refund);
        jdbc.update("update refund_request set status = 'UNKNOWN' where id = ?", refund);

        reconciliation.hourly(clock.now());
        daily();

        assertThat(jdbc.queryForObject("select status from refund_request where id = ?", String.class, refund)).isEqualTo("UNKNOWN");
        assertThat(count("select count(*) from payment where kind = 'REFUND'")).isZero();
        assertThat(count("select count(*) from billing_exception where resolution = 'REFUNDED_AUTOMATICALLY'")).isZero();
        assertThat(fakeEt.refundSubmitCalls()).isZero();
    }

    @Test
    void withTheMarketsBillingOffNothingIsListedOrRecovered() {
        String id = creating(owner, 15);
        outcomes.setBillingEnabled(Market.ET, false);

        daily();

        assertThat(fakeEt.listCalls()).isZero();
        assertThat(count("select count(*) from provider_event")).isZero();
        assertThat(fakeEt.verifyCalls()).isZero();
        assertThat(jdbc.queryForObject("select status from checkout_session where id = ?", String.class, id)).isEqualTo("CREATING");
    }

    @Test
    void theJobsExistAndTheirTriggersOnlyWithJobsOn() {
        List<String> types = context.getBeansOfType(JobHandler.class).values().stream().map(h -> ((JobHandler<?>) h).type()).toList();
        assertThat(types).contains(ReconciliationJobs.HOURLY_TYPE, ReconciliationJobs.DAILY_TYPE);
        assertThat(context.getBeanNamesForType(ReconciliationCadence.class)).isEmpty();
    }

    // ---- fixtures ----

    private String creating(Tenant t, int minutesOld) {
        jdbc.update("insert into billing_account (id, business_id, market, provider) values (?, ?, 'ET', ?) on conflict (business_id, provider) do nothing",
                UUID.randomUUID().toString(), t.businessId(), PROVIDER);
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into checkout_session (id, business_id, owner_user_id, price_id, config_version, kind, provider, status, idempotency_key, request_hash, expires_at, "
                        + "expected_amount_minor, expected_currency, created_at) values (?, ?, ?, ?, 1, 'NEW', ?, 'CREATING', ?, 'hash', now() + interval '23 hours', ?, 'ETB', now() - (? * interval '1 minute'))",
                id, t.businessId(), t.owner().getId(), price, PROVIDER, UUID.randomUUID().toString(), ET_AMOUNT, minutesOld);
        return id;
    }

    private void seedOpenFundsException(Tenant t) {
        jdbc.update("insert into billing_exception (id, business_id, code, involves_customer_funds, facts, status, created_at) values (?, ?, 'SEEDED', true, '{}'::jsonb, 'OPEN', now())",
                UUID.randomUUID().toString(), t.businessId());
    }

    /** A verified charge in the ledger with no period, refund or exception, which the provider confirms. */
    private String seedUnaccountedCharge(Tenant t) {
        String ref = "pay_unaccounted_" + UUID.randomUUID().toString().substring(0, 8);
        Instant at = soon();
        jdbc.update("insert into payment (id, business_id, provider, provider_payment_ref, amount_minor, currency, kind, status, occurred_at, verified_at, verification) "
                        + "values (?, ?, ?, ?, ?, 'ETB', 'CHARGE', 'SUCCEEDED', ?, now(), 'RECONCILIATION_API_FETCH')",
                UUID.randomUUID().toString(), t.businessId(), PROVIDER, ref, ET_AMOUNT, Timestamp.from(at));
        fakeEt.payment(new VerifiedPayment(ref, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), at, Map.of()));
        return ref;
    }
}
