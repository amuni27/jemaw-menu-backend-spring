package com.agafari.com.billing;

import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.ProviderBilling;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.service.PlanRequestService;
import com.agafari.com.subscription.EffectiveStateEvaluator;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * AGF SUB-R5-03-B (assessment story 2; addendum §13 "Owner sees"): the owner's plan view carries the renewal state of a
 * PROVIDER subscription: its grace end and past-due date, and a {@code renewal} summary (past due, ended for non-payment, or
 * a refund pending). Read-only; absent for trials, manual and owner rows. PostgreSQL; fake ET provider (SYNTHETIC answers).
 */
class OwnerRenewalStateTest extends PaymentOutcomeTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private PlanRequestService planRequests;
    @Autowired private EffectiveStateEvaluator evaluator;
    @Autowired private ObjectMapper mapper;

    private String priceRef;

    @BeforeEach
    void setUpView() {
        planRequests.setOwnerPlansVisible(true);
        evaluator.setPeriodEndEnabled(false);
        int version = jdbc.queryForObject("select max(version) + 1 from monetization_config", Integer.class);
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) "
                + "values (?, 'SETTING_2', true, false, true, ?, now(), ?)", version, Timestamp.from(Instant.now().minus(Duration.ofDays(60))), admin.getId());
        // a MODEL_4 price version carrying a provider price (prices are append-only)
        priceRef = "price_view_" + SEQ.incrementAndGet();
        price = java.util.UUID.randomUUID().toString();
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, provider_price_ref, active, valid_from) "
                + "values (?, 'MODEL_4', 'ET', 'ETB', 'MONTH', ?, 91, ?, true, now() - interval '400 days')", price, ET_AMOUNT, priceRef);
    }

    @AfterEach
    void tearDownView() {
        planRequests.setOwnerPlansVisible(false);
        evaluator.setPeriodEndEnabled(false);
        clock.reset();
    }

    private record Sub(String id, String ref, String customer, Instant periodEnd) {
    }

    /** A PROVIDER row with a provider subscription (R5-02 shape) whose period ends in five minutes. */
    private Sub provider(Tenant t) {
        String n = SEQ.incrementAndGet() + "_" + t.businessId().substring(0, 6);
        Instant end = micros(Instant.now().plus(Duration.ofMinutes(5))), start = end.atZone(java.time.ZoneOffset.UTC).minusMonths(1).toInstant(), tPay = micros(clock.now());
        String c = checkout(t, CheckoutKind.NEW, null);
        String in = "in_view_" + n;
        fakeEt.payment(new VerifiedPayment(in, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), tPay, metadata(c),
                new ProviderBilling(in, "sub_view_" + n, "cus_view_" + n, priceRef, PriceInterval.MONTH, start, end, "pi_" + n, null)));
        assertThat(process(event(in))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        return new Sub(current(t), "sub_view_" + n, "cus_view_" + n, end);
    }

    private String renewalPayment(Sub s, Instant start, Instant tPay) {
        String in = "in_view_renew_" + SEQ.incrementAndGet();
        fakeEt.payment(new VerifiedPayment(in, ProviderPaymentStatus.SUCCEEDED, new Money(ET_AMOUNT, Currency.ETB), tPay, Map.of(),
                ProviderBilling.renewal(in, s.ref(), s.customer(), priceRef, PriceInterval.MONTH, start, oneMonth(start), "pi_" + in)));
        return in;
    }

    private JsonNode view(Tenant t) throws Exception {
        return mapper.readTree(mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(t))).andReturn().getResponse().getContentAsString());
    }

    private void at(Instant t) {
        clock.setOffset(Duration.between(Instant.now(), t));
    }

    private void locked(Tenant t, Runnable work) {
        new TransactionTemplate(transactions).executeWithoutResult(x -> {
            locks.configShared();
            locks.restaurant(t.businessId());
            work.run();
        });
    }

    private static Instant instant(JsonNode n) {
        return Instant.parse(n.asString());
    }

    @Test
    void anActiveProviderRowShowsItsGraceEndAndNoRenewalSummary() throws Exception {
        Sub s = provider(owner);
        JsonNode v = view(owner);
        assertThat(v.path("current").path("status").asString()).isEqualTo("ACTIVE");
        assertThat(instant(v.path("current").path("graceUntil"))).as("AD-7: period_end + 7 d").isEqualTo(s.periodEnd().plus(Duration.ofDays(7)));
        assertThat(v.path("current").has("pastDueSince")).isFalse();
        assertThat(v.has("renewal")).isFalse();
        // the existing fields keep their names and values
        assertThat(v.path("current").path("planCode").asString()).isEqualTo("MODEL_4");
        assertThat(instant(v.path("current").path("periodEnd"))).isEqualTo(s.periodEnd());
        assertThat(v.path("current").has("outsideCurrentConfiguration")).isTrue(); // (sourceLabel is null, so omitted, for PROVIDER rows, as before)
    }

    @Test
    void aPastDueRowShowsWhenItBecamePastDueAndHowLongItKeepsAccess() throws Exception {
        Sub s = provider(owner);
        at(s.periodEnd().plus(Duration.ofHours(2)));
        locked(owner, () -> lifecycle.markPastDue(s.id(), s.periodEnd(), clock.now()));
        JsonNode v = view(owner);
        assertThat(instant(v.path("current").path("pastDueSince"))).isEqualTo(s.periodEnd());
        JsonNode r = v.path("renewal");
        assertThat(r.path("state").asString()).isEqualTo("PAST_DUE");
        assertThat(instant(r.path("periodEnd"))).isEqualTo(s.periodEnd());
        assertThat(instant(r.path("graceUntil"))).isEqualTo(s.periodEnd().plus(Duration.ofDays(7)));
        assertThat(r.path("timeZone").asString()).isNotBlank();
        assertThat(r.has("refund")).isFalse();
    }

    @Test
    void inGraceByTimeIsPastDueEvenBeforeAnyJobPersistedIt() throws Exception {
        Sub s = provider(owner);
        evaluator.setPeriodEndEnabled(true);
        at(s.periodEnd().plus(Duration.ofDays(3)));
        assertThat(jdbc.queryForObject("select status from business_subscription where id = ?", String.class, s.id())).isEqualTo("ACTIVE");
        assertThat(view(owner).path("renewal").path("state").asString()).isEqualTo("PAST_DUE");
    }

    @Test
    void anEndedPlanForNonPaymentIsShownWithItsEndAndNoCurrentPlan() throws Exception {
        Sub s = provider(owner);
        evaluator.setPeriodEndEnabled(true);
        at(s.periodEnd().plus(Duration.ofDays(8)));
        locked(owner, () -> lifecycle.materialize(owner.businessId()));
        JsonNode v = view(owner);
        assertThat(v.path("current").isMissingNode() || v.path("current").isNull()).isTrue();
        JsonNode r = v.path("renewal");
        assertThat(r.path("state").asString()).isEqualTo("ENDED_PAYMENT_FAILED");
        assertThat(instant(r.path("endedAt"))).isEqualTo(s.periodEnd().plus(Duration.ofDays(7)));
    }

    @Test
    void aPaymentAfterThePlanEndedShowsTheRefundInProgress() throws Exception {
        Sub s = provider(owner);
        evaluator.setPeriodEndEnabled(true);
        at(s.periodEnd().plus(Duration.ofDays(8)));
        locked(owner, () -> lifecycle.materialize(owner.businessId()));
        process(event(renewalPayment(s, s.periodEnd(), micros(clock.now().minusSeconds(60)))));
        JsonNode r = view(owner).path("renewal");
        assertThat(r.path("state").asString()).isEqualTo("ENDED_PAYMENT_FAILED");
        assertThat(r.path("refund").path("reason").asString()).isEqualTo(RenewalOutcome.PAID_AFTER_EXPIRY);
        assertThat(r.path("refund").path("amountMinor").asLong()).isEqualTo(ET_AMOUNT);
        assertThat(r.path("refund").path("currency").asString()).isEqualTo("ETB");
    }

    @Test
    void aDuplicateRenewalOnAnActivePlanShowsOnlyTheRefund() throws Exception {
        Sub s = provider(owner);
        Instant tPay = micros(s.periodEnd().minusSeconds(60));
        process(event(renewalPayment(s, s.periodEnd(), tPay)));
        process(event(renewalPayment(s, s.periodEnd(), tPay.plusSeconds(5))));
        JsonNode r = view(owner).path("renewal");
        assertThat(r.path("state").asString()).isEqualTo("REFUND_PENDING");
        assertThat(r.path("refund").path("reason").asString()).isEqualTo(RenewalOutcome.DUPLICATE_PERIOD);
        Tenant other = data.tenant("renewal-view-other");
        provider(other);
        assertThat(view(other).has("renewal")).as("another restaurant never sees this refund").isFalse();
    }

    @Test
    void trialAndOwnerRowsHaveNoRenewalState() throws Exception {
        startTrial(owner, clock.now().plus(10, ChronoUnit.DAYS));
        Tenant free = data.tenant("renewal-view-free");
        startOwnerFree(free);
        JsonNode trial = view(owner), own = view(free);
        assertThat(trial.has("renewal")).isFalse();
        assertThat(own.has("renewal")).isFalse();
        assertThat(own.path("current").has("graceUntil")).isFalse();
        assertThat(own.path("current").has("pastDueSince")).isFalse();
    }

    @Test
    void anotherRestaurantsRenewalStateIsNeverShown() throws Exception {
        Sub b = provider(data.tenant("renewal-view-b"));
        provider(owner);
        at(b.periodEnd().plus(Duration.ofHours(2)));
        String businessB = jdbc.queryForObject("select business_id from business_subscription where id = ?", String.class, b.id());
        new TransactionTemplate(transactions).executeWithoutResult(x -> {
            locks.configShared();
            locks.restaurant(businessB);
            lifecycle.markPastDue(b.id(), b.periodEnd(), clock.now());
        });
        JsonNode mine = view(owner);
        assertThat(mine.has("renewal")).isFalse();
        assertThat(mine.path("current").has("pastDueSince")).isFalse();
    }

    @Test
    void aHiddenViewHasNoRenewalState() throws Exception {
        provider(owner);
        planRequests.setOwnerPlansVisible(false);
        JsonNode v = view(owner);
        assertThat(v.path("visible").asBoolean()).isFalse();
        assertThat(v.has("renewal")).isFalse();
    }
}
