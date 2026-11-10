package com.agafari.com.billing;

import com.agafari.com.billing.fake.FakeStrategy;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.service.PlanRequestService;
import com.agafari.com.service.ScheduledChangeService;
import com.agafari.com.subscription.EffectiveStateEvaluator;
import com.agafari.com.subscription.PeriodEndJob;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF SUB-R4-15: paid scheduled downgrades on PostgreSQL (AC1–AC3, PO D1–D4, P1–P3, the three boundary safeguards).
 *
 * <p>Fixture note: under the approved D1 rule no paid → paid downgrade exists in today's product (Setting 2's paid plans are
 * Model 3 and Model 4, whose transition is undecided, CHG-03/04). Changes are therefore inserted as fixtures (Model 4 →
 * Model 3), and the owner API's refusal is tested separately. Grace values are fixtures only; AD-7 is open.
 */
class PaidScheduledChangeTest extends PaymentOutcomeTestBase {

    @Autowired private ScheduledChangeService scheduledChanges;
    @Autowired private PeriodEndJob periodEndJob;
    @Autowired private EffectiveStateEvaluator evaluator;
    @Autowired private ReconciliationService reconciliation;
    @Autowired private PlanRequestService planRequests;
    @Autowired private ObjectMapper mapper;

    private String price3, price4b;

    @BeforeEach
    void setUpPaidChanges() {
        price3 = price("MODEL_3", "ET", "ETB", 1500);
        price4b = price("MODEL_4", "ET", "ETB", 3000);
        configSince(2, Instant.now().minus(Duration.ofDays(60)));
        configSince(3, jdbc.queryForObject("select now()", Timestamp.class).toInstant()); // after version 1, so Model 3 is enabled from now on
        evaluator.setPeriodEndEnabled(true);
    }

    @AfterEach
    void resetPaidChanges() {
        evaluator.setPeriodEndEnabled(false);
        FakeStrategy.nextKind = CheckoutKind.NEW;
        planRequests.setOwnerPlansVisible(false);
        clock.reset();
    }

    private void configSince(int version, Instant from) {
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) "
                + "values (?, 'SETTING_2', true, true, true, ?, now(), ?)", version, Timestamp.from(from), admin.getId());
    }

    // ---- fixtures ----

    /** A PROVIDER Model 4 row whose paid period ends {@code untilEnd} from now. */
    private String paidRow(Tenant t, Duration untilEnd) {
        Instant periodEnd = micros(Instant.now().plus(untilEnd));
        Instant tPay = periodEnd.atZone(ZoneOffset.UTC).minusMonths(1).toInstant();
        String c = checkout(t, CheckoutKind.NEW, null);
        assertThat(process(event(paid(c, tPay)))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        String sub = jdbc.queryForObject("select id from business_subscription where business_id = ? and status = 'ACTIVE'", String.class, t.businessId());
        assertThat(periodEnd(sub)).isEqualTo(periodEnd);
        return sub;
    }

    private Instant periodEnd(String sub) {
        return jdbc.queryForObject("select period_end from business_subscription where id = ?", Timestamp.class, sub).toInstant();
    }

    /** A paid downgrade to Model 3 at the row's period end (fixture: see the class note). */
    private String change(Tenant t, String sub) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into scheduled_change (id, business_id, from_subscription_id, kind, target_plan_code, target_price_id, effective_at, status) "
                + "values (?, ?, ?, 'DOWNGRADE', 'MODEL_3', ?, ?, 'PENDING')", id, t.businessId(), sub, price3, Timestamp.from(periodEnd(sub)));
        jdbc.update("insert into admin_audit_event (id, occurred_at, actor_user_id, actor_label, action, target_type, target_id, outcome) values (?, now(), ?, 'owner', 'SCHEDULED_CHANGE_REQUESTED', 'SCHEDULED_CHANGE', ?, 'SUCCESS')",
                UUID.randomUUID().toString(), t.owner().getId(), id);
        return id;
    }

    /** A downgrade-renewal checkout made for {@code change}. */
    private String downgradeCheckout(Tenant t, String change, String sub, Instant expiresAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into checkout_session (id, business_id, owner_user_id, price_id, config_version, kind, replaces_subscription_id, provider, provider_session_ref, provider_session_url, "
                        + "status, idempotency_key, request_hash, expires_at, expected_amount_minor, expected_currency, created_at, scheduled_change_id) "
                        + "values (?, ?, ?, ?, 3, 'DOWNGRADE_RENEWAL', ?, ?, ?, 'https://fake.test/pay', 'OPEN', ?, 'hash', ?, 1500, 'ETB', now(), ?)",
                id, t.businessId(), t.owner().getId(), price3, sub, PROVIDER, "sess_" + id, UUID.randomUUID().toString(), Timestamp.from(expiresAt), change);
        return id;
    }

    private String downgradeCheckout(Tenant t, String change, String sub) {
        return downgradeCheckout(t, change, sub, Instant.now().plus(23, ChronoUnit.HOURS));
    }

    private Map<String, Object> changeRow(String id) {
        return jdbc.queryForMap("select * from scheduled_change where id = ?", id);
    }

    private Map<String, Object> subRow(String id) {
        return jdbc.queryForMap("select * from business_subscription where id = ?", id);
    }

    private void passTime(Duration d) {
        clock.setOffset(d);
    }

    private void periodEndJob(Tenant t, String sub) {
        periodEndJob.handle(new PeriodEndJob.Payload(t.businessId(), sub, periodEnd(sub)));
    }

    private void command(Tenant t) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            locks.configShared();
            locks.restaurant(t.businessId());
            lifecycle.materialize(t.businessId());
        });
    }

    private String targetRow(Tenant t) {
        return jdbc.queryForObject("select id from business_subscription where business_id = ? and plan_code = 'MODEL_3'", String.class, t.businessId());
    }

    private void assertApplied(Tenant t, String sub, String changeId) {
        Instant boundary = ((Timestamp) changeRow(changeId).get("effective_at")).toInstant();
        String target = targetRow(t);
        Map<String, Object> old = subRow(sub), next = subRow(target);
        assertThat(old).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "CHANGED").containsEntry("replaced_by_id", target);
        assertThat(((Timestamp) old.get("ended_at")).toInstant()).as("ends exactly at the boundary").isEqualTo(boundary);
        assertThat(next).containsEntry("source", "PROVIDER").containsEntry("plan_code", "MODEL_3").containsEntry("price_id", price3);
        assertThat(next.get("grace_days")).as("AD-7: no grace invented").isNull();
        assertThat(((Timestamp) next.get("period_end")).toInstant()).isEqualTo(oneMonth(boundary));
        Map<String, Object> change = changeRow(changeId);
        assertThat(change).containsEntry("status", "APPLIED").containsEntry("applied_subscription_id", target);
        assertThat(jdbc.queryForObject("select period_start from subscription_period where subscription_id = ? and payment_id = ?", Timestamp.class, target, change.get("funding_payment_id"))
                .toInstant()).as("the target period starts at the boundary, never at t_pay").isEqualTo(boundary);
        assertEveryChargeAccountedForOnce();
    }

    private String fundEarly(Tenant t, String sub, String changeId) {
        String co = downgradeCheckout(t, changeId, sub);
        String ref = paid(co, micros(Instant.now().plusSeconds(5)));
        assertThat(process(event(ref))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        assertThat(changeRow(changeId).get("funding_payment_id")).isNotNull();
        return ref;
    }

    // ---- AC1, NC-08: early payment grants nothing ----

    @Test
    void ac1AnEarlyVerifiedPaymentIsHeldAndTheCurrentPlanContinuesUntilTheBoundary() throws Exception {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        String changeId = change(owner, sub);
        String co = downgradeCheckout(owner, changeId, sub);
        String ref = paid(co, micros(Instant.now().plusSeconds(5)));

        assertThat(process(event(ref))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);

        Map<String, Object> change = changeRow(changeId);
        assertThat(change).containsEntry("status", "PENDING");
        assertThat(change.get("funding_payment_id")).isEqualTo(jdbc.queryForObject("select id from payment where provider_payment_ref = ?", String.class, ref));
        assertThat(current(owner)).as("NC-08: the held payment grants nothing").isEqualTo(sub);
        assertThat(subRow(sub)).containsEntry("plan_code", "MODEL_4").containsEntry("status", "ACTIVE");
        assertThat(providerSubscriptions(owner)).isEqualTo(1);
        assertThat(checkoutStatus(co)).isEqualTo("COMPLETED");
        planRequests.setOwnerPlansVisible(true);
        String view = mockMvc.perform(get("/api/plan").header("Authorization", data.bearer(owner))).andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(view).path("current").path("planCode").asString()).isEqualTo("MODEL_4");
        reconciliation.daily(clock.now());
        assertThat(count("select count(*) from billing_exception where code = 'RECONCILIATION_CHARGE_UNACCOUNTED'")).as("D3: a held charge is accounted while its association is valid").isZero();

        command(owner);
        assertThat(current(owner)).as("still before the boundary").isEqualTo(sub);
    }

    // ---- D2, D4: the boundary, by every path ----

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"change-job", "period-end-job-first", "any-lifecycle-command"})
    void aFundedDowngradeAppliesExactlyAtItsBoundaryWhicheverJobRunsFirst(String path) {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        String changeId = change(owner, sub);
        fundEarly(owner, sub, changeId);
        passTime(Duration.ofMinutes(10));

        switch (path) {
            case "change-job" -> assertThat(scheduledChanges.apply(changeId)).isTrue();
            case "period-end-job-first" -> periodEndJob(owner, sub);
            default -> command(owner);
        }

        assertApplied(owner, sub, changeId);
        scheduledChanges.apply(changeId);
        periodEndJob(owner, sub);
        command(owner);
        assertThat(count("select count(*) from business_subscription where business_id = ? and plan_code = 'MODEL_3'", owner.businessId())).as("applied once").isEqualTo(1);
    }

    @Test
    void bothBoundaryJobsAtOnceApplyTheChangeOnce() throws Exception {
        for (int round = 0; round < 4; round++) {
            Tenant t = data.tenant("paid-change-race-" + round);
            String sub = paidRow(t, Duration.ofMinutes(5));
            String changeId = change(t, sub);
            fundEarly(t, sub, changeId);
            passTime(Duration.ofMinutes(10));

            race(2, i -> () -> {
                if (i == 0) scheduledChanges.apply(changeId);
                else periodEndJob(t, sub);
                return 0;
            });

            assertApplied(t, sub, changeId);
            clock.reset();
        }
    }

    @Test
    void withPeriodEndEnforcementOffAHeldDowngradeIsNeitherAppliedNorFailed() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        String changeId = change(owner, sub);
        fundEarly(owner, sub, changeId);
        evaluator.setPeriodEndEnabled(false); // as everywhere today
        passTime(Duration.ofMinutes(10));

        scheduledChanges.apply(changeId);
        command(owner);

        assertThat(changeRow(changeId).get("status")).isEqualTo("PENDING");
        assertThat(current(owner)).isEqualTo(sub);
        reconciliation.daily(clock.now());
        assertThat(count("select count(*) from billing_exception where code = 'RECONCILIATION_CHARGE_UNACCOUNTED'")).isZero();
    }

    // ---- AC2: failure keeps only the existing contract, under recorded grace ----

    @Test
    void ac2WithoutPaymentTheCurrentPlanContinuesOnlyThroughRecordedGraceThenTheChangeFails() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        jdbc.update("update business_subscription set grace_days = 1 where id = ?", sub); // explicit fixture grace
        String changeId = change(owner, sub);
        passTime(Duration.ofMinutes(10));

        periodEndJob(owner, sub);

        assertThat(changeRow(changeId).get("status")).as("grants nothing").isEqualTo("AWAITING_PAYMENT");
        assertThat(subRow(sub)).containsEntry("status", "PAST_DUE").containsEntry("plan_code", "MODEL_4");
        assertThat(current(owner)).as("the existing contract, never the target").isEqualTo(sub);
        assertThat(count("select count(*) from business_subscription where business_id = ? and plan_code = 'MODEL_3'", owner.businessId())).isZero();

        passTime(Duration.ofDays(1).plusMinutes(10));
        periodEndJob(owner, sub);

        assertThat(subRow(sub)).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "PAYMENT_FAILED");
        assertThat(((Timestamp) subRow(sub).get("ended_at")).toInstant()).isEqualTo(periodEnd(sub).plus(Duration.ofDays(1)));
        assertThat(changeRow(changeId).get("status")).isEqualTo("FAILED");
        assertThat(current(owner)).isNull();
    }

    @Test
    void ac2WithoutRecordedGraceTheRowEndsAtTheBoundaryAndTheChangeFails() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        String changeId = change(owner, sub);
        passTime(Duration.ofMinutes(10));

        periodEndJob(owner, sub);

        assertThat(subRow(sub)).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "PAYMENT_FAILED");
        assertThat(changeRow(changeId).get("status")).isEqualTo("FAILED");
        assertThat(count("select count(*) from billing_exception where business_id = ?", owner.businessId())).as("nothing was held").isZero();
    }

    @Test
    void d2APaymentWithinRecordedGraceAppliesTheTargetFromTheBoundary() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        jdbc.update("update business_subscription set grace_days = 1 where id = ?", sub);
        String changeId = change(owner, sub);
        Instant boundary = periodEnd(sub);
        passTime(Duration.ofMinutes(10));
        periodEndJob(owner, sub);
        assertThat(subRow(sub).get("status")).isEqualTo("PAST_DUE");

        String co = downgradeCheckout(owner, changeId, sub);
        assertThat(process(event(paid(co, boundary.plus(Duration.ofMinutes(3)))))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);

        assertApplied(owner, sub, changeId);
    }

    // ---- boundary safeguard 2, N5 ----

    @Test
    void n5APaymentAfterTheDeadlineIsRefusedEvenBeforeTheExpiryJobRunsAndNeverActivates() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        String changeId = change(owner, sub);
        String co = downgradeCheckout(owner, changeId, sub);
        Instant boundary = periodEnd(sub);
        passTime(Duration.ofMinutes(10));
        assertThat(subRow(sub).get("status")).as("the expiry job hasn't run").isEqualTo("ACTIVE");
        String ref = paid(co, boundary.plusSeconds(60));

        assertThat(process(event(ref))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);

        String paymentId = jdbc.queryForObject("select id from payment where provider_payment_ref = ?", String.class, ref);
        assertThat(jdbc.queryForMap("select code, status, involves_customer_funds from billing_exception where payment_id = ?", paymentId))
                .containsEntry("code", PaymentOutcome.STATE_CHANGED).containsEntry("status", "OPEN").containsEntry("involves_customer_funds", true);
        assertThat(changeRow(changeId).get("funding_payment_id")).isNull();
        assertThat(count("select count(*) from business_subscription where business_id = ? and plan_code = 'MODEL_3'", owner.businessId())).as("never row 7").isZero();
        assertThat(jdbc.queryForObject("select invoice_id from payment where id = ?", String.class, paymentId)).isNull();

        periodEndJob(owner, sub);
        assertThat(changeRow(changeId).get("status")).isEqualTo("FAILED");
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void d4AnOnTimePaymentSeenAfterTheBoundaryButBeforeTheExpiryJobIsJudgedByItsPaymentTime() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        String changeId = change(owner, sub);
        String co = downgradeCheckout(owner, changeId, sub);
        Instant boundary = periodEnd(sub);
        String ref = paid(co, boundary.minusSeconds(30)); // paid before the deadline
        passTime(Duration.ofMinutes(10)); // seen after it; nothing has expired the row yet

        assertThat(process(event(ref))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);

        assertApplied(owner, sub, changeId);
    }

    @Test
    void aPaymentForAChangeAlreadyCancelledNeverActivatesTheTarget() {
        String sub = paidRow(owner, Duration.ofHours(2));
        String changeId = change(owner, sub);
        String co = downgradeCheckout(owner, changeId, sub);
        scheduledChanges.cancelPending(owner.businessId(), owner.owner().getId(), "o@test.agafari.local");
        String ref = paid(co, micros(Instant.now().plusSeconds(5)));

        process(event(ref));

        String paymentId = jdbc.queryForObject("select id from payment where provider_payment_ref = ?", String.class, ref);
        assertThat(jdbc.queryForObject("select code from billing_exception where payment_id = ?", String.class, paymentId)).isEqualTo(PaymentOutcome.STATE_CHANGED);
        assertThat(current(owner)).as("boundary safeguard 2: never an ordinary row-7 replacement").isEqualTo(sub);
        assertThat(count("select count(*) from business_subscription where business_id = ? and plan_code = 'MODEL_3'", owner.businessId())).isZero();
    }

    @Test
    void aPaymentThroughAnEarlierChangesCheckoutNeverFundsANewerChange() {
        String sub = paidRow(owner, Duration.ofHours(2));
        String first = change(owner, sub);
        String oldCheckout = downgradeCheckout(owner, first, sub);
        scheduledChanges.cancelPending(owner.businessId(), owner.owner().getId(), "o@test.agafari.local");
        String second = change(owner, sub); // same row, same target price
        String ref = paid(oldCheckout, micros(Instant.now().plusSeconds(5)));

        process(event(ref));

        assertThat(changeRow(second).get("funding_payment_id")).as("D3: no inheritance").isNull();
        String paymentId = jdbc.queryForObject("select id from payment where provider_payment_ref = ?", String.class, ref);
        assertThat(jdbc.queryForObject("select code from billing_exception where payment_id = ?", String.class, paymentId)).isEqualTo(PaymentOutcome.STATE_CHANGED);
        assertThat(current(owner)).isEqualTo(sub);
    }

    // ---- P3, N4: on time, discovered after FAILED ----

    @Test
    void n4AnOnTimePaymentDiscoveredAfterTheChangeFailedGoesToStateChangedReviewAtomically() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        String changeId = change(owner, sub);
        String co = downgradeCheckout(owner, changeId, sub);
        Instant boundary = periodEnd(sub);
        String ref = paid(co, boundary.minusSeconds(30)); // paid in time
        passTime(Duration.ofMinutes(10));
        periodEndJob(owner, sub); // the expiry ran before the notification arrived
        assertThat(changeRow(changeId).get("status")).isEqualTo("FAILED");

        assertThat(process(event(ref))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);

        String paymentId = jdbc.queryForObject("select id from payment where provider_payment_ref = ?", String.class, ref);
        assertThat(jdbc.queryForObject("select code from billing_exception where payment_id = ?", String.class, paymentId)).isEqualTo(PaymentOutcome.STATE_CHANGED);
        assertThat(changeRow(changeId).get("funding_payment_id")).as("a failed change can't be funded").isNull();
        assertThat(count("select count(*) from business_subscription where business_id = ? and plan_code = 'MODEL_3'", owner.businessId())).isZero();
        assertThat(refunds(owner)).as("no refund before PD-9").isZero();
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void n4MatrixPrecedenceStillAppliesAPaymentAfterItsCheckoutExpiredIsALatePayment() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        String changeId = change(owner, sub);
        Instant boundary = periodEnd(sub);
        String co = downgradeCheckout(owner, changeId, sub, boundary.minusSeconds(90));
        String ref = paid(co, boundary.minusSeconds(30)); // before the deadline, after the checkout expired
        passTime(Duration.ofMinutes(10));
        periodEndJob(owner, sub);

        process(event(ref));

        String paymentId = jdbc.queryForObject("select id from payment where provider_payment_ref = ?", String.class, ref);
        assertThat(jdbc.queryForObject("select code from billing_exception where payment_id = ?", String.class, paymentId)).isEqualTo(PaymentOutcome.LATE_PAYMENT);
        assertThat(refunds(owner)).isEqualTo(1);
    }

    // ---- N6: duplicate payments ----

    @Test
    void n6ASecondPaymentForAFundedChangeIsADuplicateAndTheFundingDoesntMove() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        String changeId = change(owner, sub);
        String co = downgradeCheckout(owner, changeId, sub);
        process(event(paid(co, micros(Instant.now().plusSeconds(5)))));
        Object funding = changeRow(changeId).get("funding_payment_id");
        String second = paid(co, micros(Instant.now().plusSeconds(6)));

        process(event(second));

        String secondId = jdbc.queryForObject("select id from payment where provider_payment_ref = ?", String.class, second);
        assertThat(jdbc.queryForObject("select code from billing_exception where payment_id = ?", String.class, secondId)).isEqualTo(PaymentOutcome.DUPLICATE_PAYMENT);
        assertThat(jdbc.queryForObject("select status from refund_request where payment_id = ?", String.class, secondId)).isEqualTo("REQUESTED");
        assertThat(changeRow(changeId).get("funding_payment_id")).isEqualTo(funding);

        passTime(Duration.ofMinutes(10));
        periodEndJob(owner, sub);
        assertApplied(owner, sub, changeId);
    }

    // ---- N7: rescheduling to the same target ----

    @Test
    void n7AChangeRescheduledToTheSameTargetNeverInheritsTheEarlierPayment() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        String first = change(owner, sub);
        String ref = fundEarly(owner, sub, first);
        String held = (String) changeRow(first).get("funding_payment_id");

        scheduledChanges.cancelPending(owner.businessId(), owner.owner().getId(), "owner@test.agafari.local");

        assertThat(changeRow(first)).containsEntry("status", "CANCELLED").containsEntry("funding_payment_id", held);
        assertThat(jdbc.queryForObject("select code from billing_exception where payment_id = ?", String.class, held)).isEqualTo(PaymentOutcome.STATE_CHANGED);
        String second = change(owner, sub);
        assertThatThrownBy(() -> jdbc.update("update scheduled_change set funding_payment_id = ?, funding_invoice_id = (select invoice_id from payment where id = ?), funded_at = now() where id = ?",
                held, held, second)).isNotNull();
        process(event(ref)); // the old payment's event again
        assertThat(changeRow(second).get("funding_payment_id")).isNull();

        passTime(Duration.ofMinutes(10));
        periodEndJob(owner, sub);
        assertThat(changeRow(second).get("status")).isEqualTo("FAILED");
        assertThat(count("select count(*) from business_subscription where business_id = ? and plan_code = 'MODEL_3'", owner.businessId())).isZero();
        assertEveryChargeAccountedForOnce();
    }

    // ---- N8, AC3: supersession ----

    @Test
    void ac3AnUpgradeSupersedesAHeldDowngradeAtomicallyAndItsChargeGoesToReview() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        String changeId = change(owner, sub);
        fundEarly(owner, sub, changeId);
        String held = (String) changeRow(changeId).get("funding_payment_id");
        String upgrade = checkout(owner, CheckoutKind.UPGRADE, sub, price4b, 3000, Instant.now().plus(23, ChronoUnit.HOURS));

        assertThat(payAndProcess(upgrade, micros(Instant.now().plusSeconds(8)))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);

        assertThat(changeRow(changeId)).containsEntry("status", "SUPERSEDED").containsEntry("funding_payment_id", held);
        assertThat(jdbc.queryForMap("select code, involves_customer_funds from billing_exception where payment_id = ?", held))
                .containsEntry("code", PaymentOutcome.STATE_CHANGED).containsEntry("involves_customer_funds", true);
        assertThat(subRow(sub).get("status")).isEqualTo("EXPIRED");
        reconciliation.daily(clock.now());
        assertThat(count("select count(*) from billing_exception where payment_id = ?", held)).as("linked once, not flagged again").isEqualTo(1);
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void ac3ARefusedUpgradeLeavesTheHeldDowngradeUntouched() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        String changeId = change(owner, sub);
        fundEarly(owner, sub, changeId);
        String held = (String) changeRow(changeId).get("funding_payment_id");
        String upgrade = checkout(owner, CheckoutKind.UPGRADE, sub, price4b, 2999, Instant.now().plus(23, ChronoUnit.HOURS)); // the lifecycle refuses the amount

        payAndProcess(upgrade, micros(Instant.now().plusSeconds(8)));

        assertThat(changeRow(changeId)).containsEntry("status", "PENDING").containsEntry("funding_payment_id", held);
        assertThat(count("select count(*) from billing_exception where payment_id = ?", held)).isZero();
        assertThat(current(owner)).isEqualTo(sub);
    }

    @Test
    void n8ANewOwnerRequestSupersedesAHeldDowngradeAndItsChargeGoesToReview() {
        String sub = paidRow(owner, Duration.ofHours(2));
        String changeId = change(owner, sub);
        fundEarly(owner, sub, changeId);
        String held = (String) changeRow(changeId).get("funding_payment_id");

        scheduledChanges.scheduleDowngrade(owner.businessId(), "MODEL_2", owner.owner().getId(), "owner@test.agafari.local");

        assertThat(changeRow(changeId).get("status")).isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("select code from billing_exception where payment_id = ?", String.class, held)).isEqualTo(PaymentOutcome.STATE_CHANGED);
    }

    // ---- boundary safeguard 3 ----

    @Test
    void processingThatResumesAfterTheTargetPeriodEndedNeverGrantsBeyondThePaidPeriod() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        String changeId = change(owner, sub);
        fundEarly(owner, sub, changeId);
        Instant boundary = periodEnd(sub);
        passTime(Duration.ofDays(40)); // past the target's own paid month

        periodEndJob(owner, sub);

        assertThat(changeRow(changeId).get("status")).isEqualTo("APPLIED");
        Map<String, Object> target = subRow(targetRow(owner));
        assertThat(target).containsEntry("status", "EXPIRED");
        assertThat(((Timestamp) target.get("ended_at")).toInstant()).as("not one moment beyond its paid period").isEqualTo(oneMonth(boundary));
        assertThat(current(owner)).isNull();
    }

    // ---- refused evidence at the boundary ----

    @Test
    void aHeldPaymentWhoseEvidenceNoLongerValidatesFailsTheChangeAndGoesToReview() {
        String sub = paidRow(owner, Duration.ofMinutes(5));
        String changeId = change(owner, sub);
        fundEarly(owner, sub, changeId);
        String held = (String) changeRow(changeId).get("funding_payment_id");
        jdbc.update("update billing_account set provider = 'ELSEWHERE' where business_id = ?", owner.businessId()); // the market check now fails
        passTime(Duration.ofMinutes(10));

        periodEndJob(owner, sub);

        assertThat(changeRow(changeId).get("status")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select code from billing_exception where payment_id = ?", String.class, held)).isEqualTo("EVIDENCE_INVALID");
        assertThat(count("select count(*) from business_subscription where business_id = ? and plan_code = 'MODEL_3'", owner.businessId())).isZero();
        assertThat(subRow(sub).get("status")).isEqualTo("EXPIRED");
    }

    // ---- P2: the normal checkout flow ----

    @Test
    void p2TheCheckoutFlowNamesTheChangeRetriesAndRefusesASecondPayableCheckout() throws Exception {
        String sub = paidRow(owner, Duration.ofHours(2));
        String changeId = change(owner, sub);
        jdbc.update("update checkout_session set status = 'COMPLETED' where business_id = ? and status = 'OPEN'", owner.businessId());
        FakeStrategy.nextKind = CheckoutKind.DOWNGRADE_RENEWAL;

        assertThat(httpStatus(owner, "k-down-1", price3)).isEqualTo(201);
        Map<String, Object> first = jdbc.queryForMap("select id, scheduled_change_id, replaces_subscription_id, kind from checkout_session where idempotency_key = 'k-down-1'");
        assertThat(first).containsEntry("scheduled_change_id", changeId).containsEntry("replaces_subscription_id", sub).containsEntry("kind", "DOWNGRADE_RENEWAL");
        assertThat(httpStatus(owner, "k-down-1", price3)).as("a retry returns the same checkout").isEqualTo(200);
        assertThat(count("select count(*) from checkout_session where scheduled_change_id = ?", changeId)).isEqualTo(1);

        checkouts.cancel(owner.businessId(), (String) first.get("id"));
        MockHttpServletResponse second = perform(owner, "k-down-2", price3).andReturn().getResponse();
        assertThat(second.getStatus()).as("the cancelled checkout can still be paid").isEqualTo(409);
        assertThat(second.getContentAsString()).contains("CHECKOUT_ACTIVE");
        assertThat(checkoutStatus((String) first.get("id"))).isEqualTo("CANCEL_REQUESTED");

        jdbc.update("update checkout_session set status = 'CANCELLED' where id = ?", first.get("id"));
        assertThat(httpStatus(owner, "k-down-3", price3)).as("once it can't be paid, a new one is allowed").isEqualTo(201);
    }

    @Test
    void p2ADowngradeRenewalCheckoutWithoutAnOpenChangeIsRefused() throws Exception {
        paidRow(owner, Duration.ofHours(2));
        jdbc.update("update checkout_session set status = 'COMPLETED' where business_id = ? and status = 'OPEN'", owner.businessId());
        FakeStrategy.nextKind = CheckoutKind.DOWNGRADE_RENEWAL;

        MockHttpServletResponse r = perform(owner, "k-down-none", price3).andReturn().getResponse();

        assertThat(r.getStatus()).isEqualTo(422);
        assertThat(r.getContentAsString()).contains("CHECKOUT_NO_SCHEDULED_CHANGE");
    }

    // ---- D1: owner scheduling ----

    @Test
    void d1TodaysOnlyPaidPairIsUndecidedSoTheOwnerCantScheduleIt() {
        String sub = paidRow(owner, Duration.ofHours(2));

        assertThatThrownBy(() -> scheduledChanges.scheduleDowngrade(owner.businessId(), "MODEL_3", owner.owner().getId(), "o@test.agafari.local"))
                .hasMessageContaining("aren't available yet");
        assertThat(count("select count(*) from scheduled_change where business_id = ?", owner.businessId())).isZero();
        assertThat(current(owner)).isEqualTo(sub);
    }

    @Test
    void d1PaidSchedulingNeedsTheMarketsBillingGateAndFreeTargetsAreUnaffected() {
        paidRow(owner, Duration.ofHours(2));
        outcomes.setBillingEnabled(Market.ET, false);

        assertThatThrownBy(() -> scheduledChanges.scheduleDowngrade(owner.businessId(), "MODEL_3", owner.owner().getId(), "o@test.agafari.local"))
                .isInstanceOf(com.agafari.com.exception.FieldValidationException.class); // exactly as before SUB-R4-15
        scheduledChanges.scheduleDowngrade(owner.businessId(), "MODEL_2", owner.owner().getId(), "o@test.agafari.local");
        assertThat(jdbc.queryForMap("select target_plan_code, target_price_id from scheduled_change where business_id = ?", owner.businessId()))
                .containsEntry("target_plan_code", "MODEL_2").containsEntry("target_price_id", null);
    }

    // ---- story concurrency: a downgrade payment vs a cancel of the change ----

    @Test
    void aDowngradePaymentRacingTheOwnersCancellationLeavesTheChargeAccountedOnce() throws Exception {
        int fundedThenCancelled = 0, cancelledFirst = 0;
        for (int round = 0; round < 6; round++) {
            Tenant t = data.tenant("paid-change-cancel-" + round);
            String sub = paidRow(t, Duration.ofHours(2));
            String changeId = change(t, sub);
            String co = downgradeCheckout(t, changeId, sub);
            String e = event(paid(co, micros(Instant.now().plusSeconds(5))));
            CountDownLatch fetched = new CountDownLatch(1);
            fakeEt.onVerify(fetched::countDown);
            int r = round;

            race(2, i -> i == 0 ? () -> { process(e); return 0; } : () -> {
                if (r % 2 == 0) {
                    fetched.await(10, TimeUnit.SECONDS);
                    Thread.sleep(20);
                }
                scheduledChanges.cancelPending(t.businessId(), t.owner().getId(), "o@test.agafari.local");
                return 0;
            });
            fakeEt.onVerify(() -> { });

            assertThat(changeRow(changeId).get("status")).isEqualTo("CANCELLED");
            assertThat(count("select count(*) from business_subscription where business_id = ? and plan_code = 'MODEL_3'", t.businessId())).isZero();
            assertThat(count("select count(*) from billing_exception where business_id = ? and code = 'STATE_CHANGED'", t.businessId())).as("the charge goes to review once").isEqualTo(1);
            if (changeRow(changeId).get("funding_payment_id") != null) fundedThenCancelled++;
            else cancelledFirst++;
        }
        assertThat(fundedThenCancelled).as("the payment won some rounds").isPositive();
        assertThat(cancelledFirst).as("the cancellation won some rounds").isPositive();
        assertEveryChargeAccountedForOnce();
    }

    @SuppressWarnings("unused")
    private static List<String> none() {
        return List.of();
    }
}
