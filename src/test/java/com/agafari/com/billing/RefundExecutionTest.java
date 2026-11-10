package com.agafari.com.billing;

import com.agafari.com.billing.RefundService.Outcome;
import com.agafari.com.billing.fake.FakeProviderClient;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.ProviderCapabilities;
import com.agafari.com.jobs.PermanentJobException;
import com.agafari.com.jobs.TransientJobException;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * AGF SUB-R4-13: refund submission with the fake provider on PostgreSQL (AC1–AC6, MT-04 a–g). Approved scope (PO B1, B2):
 * a refund ends at SUBMITTED here; SUCCEEDED, the REFUND ledger row and resolving the exception need verified provider
 * evidence, which the separate settlement story will provide.
 */
class RefundExecutionTest extends PaymentOutcomeTestBase {

    @org.springframework.beans.factory.annotation.Autowired private RefundService refunds;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** Row 3 (a late payment) with a confirmed idempotent refund capability: one REQUESTED refund and its AUTO_HANDLING exception. */
    private String lateRefund(Tenant t) {
        Instant expires = micros(Instant.now().plus(1, ChronoUnit.HOURS));
        process(event(paid(checkout(t, CheckoutKind.NEW, null, price, ET_AMOUNT, expires), expires.plusSeconds(1))));
        return jdbc.queryForObject("select r.id from refund_request r join payment p on p.id = r.payment_id where p.business_id = ?", String.class, t.businessId());
    }

    private Map<String, Object> refund(String id) {
        return jdbc.queryForMap("select * from refund_request where id = ?", id);
    }

    private Map<String, Object> exceptionFor(String refundId) {
        return jdbc.queryForMap("select * from billing_exception where refund_request_id = ?", refundId);
    }

    private void later(Duration d) {
        clock.setOffset(d);
    }

    private int handBacks(String refundId, String reason) {
        return count("select count(*) from admin_audit_event where action = 'BILLING_EXCEPTION_HANDED_BACK' and detail like ?", "%reason=" + reason + "%refund=" + refundId + "%");
    }

    private void assertNeverSettled() {
        assertThat(count("select count(*) from refund_request where status in ('SUCCEEDED', 'FAILED')")).as("B2: no settlement in this story").isZero();
        assertThat(count("select count(*) from payment where kind = 'REFUND'")).as("no REFUND ledger row without verified evidence").isZero();
        assertThat(count("select count(*) from billing_exception where resolution = 'REFUNDED_AUTOMATICALLY'")).isZero();
    }

    // ---- request (B3, AC6) ----

    @Test
    void theMatrixRequestsOneFullRefundAndQueuesItsExecution() {
        String id = lateRefund(owner);
        Map<String, Object> r = refund(id);

        assertThat(r.get("status")).isEqualTo("REQUESTED");
        assertThat(r.get("amount_minor")).isEqualTo(ET_AMOUNT);
        assertThat(count("select count(*) from job where type = 'refund.execute' and key = ?", id)).isEqualTo(1);
        assertThat(exceptionFor(id).get("status")).isEqualTo("AUTO_HANDLING");
    }

    @Test
    void ac6ConcurrentRequestsForOnePaymentLeaveOneRefundRequest() throws Exception {
        String first = lateRefund(owner);
        String payment = (String) refund(first).get("payment_id");

        List<Integer> created = race(4, i -> () -> {
            String r = new TransactionTemplate(transactions).execute(s -> {
                locks.configShared();
                locks.restaurant(owner.businessId());
                try {
                    return refunds.request(owner.businessId(), payment, "LATE_PAYMENT", i % 2 == 0 ? "outcome:LATE_PAYMENT" : "another-key-" + i);
                } catch (IllegalStateException refused) {
                    return null;
                }
            });
            return r != null && !r.equals(first) ? 1 : 0;
        });

        assertThat(created.stream().mapToInt(Integer::intValue).sum()).as("no second request").isZero();
        assertThat(count("select count(*) from refund_request where payment_id = ?", payment)).isEqualTo(1);
    }

    @Test
    void b3OnlyAFullRefundOfAVerifiedChargeAndNoMoreThanTheCharge() {
        String first = lateRefund(owner);
        String payment = (String) refund(first).get("payment_id");

        for (String key : List.of("second-key", "third-key")) {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> {
                locks.configShared();
                locks.restaurant(owner.businessId());
                refunds.request(owner.businessId(), payment, "LATE_PAYMENT", key);
            })).as("a non-failed request already covers the whole charge").isInstanceOf(IllegalStateException.class);
        }
        assertThat(count("select coalesce(sum(amount_minor), 0) from refund_request where payment_id = ? and status not in ('FAILED', 'FAILED_DEFINITE')", payment)).isEqualTo((int) ET_AMOUNT);
    }

    /** A verified charge with no refund request: row 6, where the current subscription changed before the payment arrived. */
    private String unrefundedCharge(Tenant t) {
        String c = checkout(t, CheckoutKind.NEW, null);
        com.agafari.com.support.MonetizationFixtures.subscription(jdbc, t.businessId(), "MODEL_4", 1, admin.getId());
        process(event(paid(c, micros(Instant.now().plus(1, ChronoUnit.MINUTES)))));
        String payment = jdbc.queryForObject("select id from payment where business_id = ? and kind = 'CHARGE'", String.class, t.businessId());
        assertThat(count("select count(*) from refund_request where payment_id = ?", payment)).isZero();
        return payment;
    }

    @Test
    void b3AFullRefundOfAnUnrefundedVerifiedChargeIsAccepted() {
        String payment = unrefundedCharge(owner);

        String id = new TransactionTemplate(transactions).execute(s -> {
            locks.configShared();
            locks.restaurant(owner.businessId());
            return refunds.request(owner.businessId(), payment, "TEST", "k");
        });

        assertThat(refund(id)).containsEntry("status", "REQUESTED").containsEntry("amount_minor", ET_AMOUNT);
    }

    @Test
    void b3AnotherRestaurantsPaymentIsRefused() {
        String payment = unrefundedCharge(owner);
        Tenant other = data.tenant("refund-other");

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> {
            locks.configShared();
            locks.restaurant(other.businessId());
            refunds.request(other.businessId(), payment, "LATE_PAYMENT", "k");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("another restaurant");
        assertThat(count("select count(*) from refund_request where payment_id = ?", payment)).isZero();
    }

    @Test
    void aRequestOutsideTheLocksIsRefused() {
        String payment = unrefundedCharge(owner);

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> refunds.request(owner.businessId(), payment, "LATE_PAYMENT", "k")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lock");
        assertThat(count("select count(*) from refund_request where payment_id = ?", payment)).isZero();
    }

    // ---- submission ----

    @Test
    void aRefundIsSubmittedOnceWithItsKeyCommittedFirstAndStaysUnsettled() {
        String id = lateRefund(owner);
        CountDownLatch seen = new CountDownLatch(1);
        Map<String, Object>[] during = new Map[1];
        fakeEt.onRefund(() -> {
            during[0] = jdbc.queryForMap("select status, idempotency_key, submitted_at from refund_request where id = ?", id);
            seen.countDown();
        });

        assertThat(refunds.execute(id)).isEqualTo(Outcome.SUBMITTED);

        assertThat(during[0]).as("SUBMITTING is committed before the call").containsEntry("status", "SUBMITTING").containsEntry("idempotency_key", "refund:" + id);
        assertThat(during[0].get("submitted_at")).isNotNull();
        Map<String, Object> r = refund(id);
        assertThat(r.get("status")).isEqualTo("SUBMITTED");
        assertThat((String) r.get("provider_refund_ref")).startsWith("fake_refund_");
        assertThat(fakeEt.refundSubmitCalls()).isEqualTo(1);
        assertThat(fakeEt.refundsCreated()).isEqualTo(1);
        assertThat(fakeEt.refundRanInTransaction()).isFalse();
        assertThat(exceptionFor(id).get("status")).as("B2: SUBMITTED is not SUCCEEDED").isEqualTo("AUTO_HANDLING");
        assertNeverSettled();

        assertThat(refunds.execute(id)).as("idempotent").isEqualTo(Outcome.NOTHING_TO_DO);
        assertThat(fakeEt.refundSubmitCalls()).isEqualTo(1);
    }

    @Test
    void anAutomaticRefundInProgressStillCantBeResolvedByHand() throws Exception {
        String id = lateRefund(owner);
        refunds.execute(id);
        Map<String, Object> x = exceptionFor(id);

        int status = mockMvc.perform(post("/api/admin/billing/exceptions/" + x.get("id") + "/resolve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVersion\":" + x.get("row_version") + ",\"resolution\":\"FUNDS_HANDLED_EXTERNALLY\",\"note\":\"n\"}")
                .header("Authorization", data.adminBearer(admin))).andReturn().getResponse().getStatus();

        assertThat(status).as("Q1").isEqualTo(409);
    }

    // ---- AC1, MT-04 a ----

    @Test
    void ac1ATimeoutInsideTheWindowIsRetriedWithTheSameKeyAndMakesOneRefund() {
        fakeEt.script(FakeProviderClient.Script.TIMEOUT_AFTER_ACCEPT);
        String id = lateRefund(owner);

        assertThatThrownBy(() -> refunds.execute(id)).isInstanceOf(TransientJobException.class);
        assertThat(refund(id).get("status")).isEqualTo("UNKNOWN");
        assertThat(exceptionFor(id).get("status")).isEqualTo("AUTO_HANDLING");

        later(Duration.ofHours(1));
        assertThat(refunds.execute(id)).isEqualTo(Outcome.SUBMITTED);

        assertThat(fakeEt.refundSubmitCalls()).as("one retry").isEqualTo(2);
        assertThat(fakeEt.refundsCreated()).as("one refund at the provider").isEqualTo(1);
        assertThat(refund(id)).containsEntry("status", "SUBMITTED").containsEntry("idempotency_key", "refund:" + id);
        assertNeverSettled();
    }

    // ---- AC2, MT-04 b ----

    @Test
    void ac2ATimeoutRetriedAfterTheWindowIsNeverResubmittedAndIsHandedBack() {
        fakeEt.script(FakeProviderClient.Script.TIMEOUT_AFTER_ACCEPT);
        String id = lateRefund(owner);
        assertThatThrownBy(() -> refunds.execute(id)).isInstanceOf(TransientJobException.class);
        fakeEt.script(FakeProviderClient.Script.LOOKUP_LAG).laggedLookups(100);

        later(Duration.ofHours(25));
        assertThat(refunds.execute(id)).isEqualTo(Outcome.HANDED_BACK);

        assertThat(fakeEt.refundSubmitCalls()).as("no submission after the window").isEqualTo(1);
        assertThat(refund(id).get("status")).as("unknown stays unknown").isEqualTo("UNKNOWN");
        assertThat(exceptionFor(id)).containsEntry("status", "OPEN").containsEntry("code", PaymentOutcome.LATE_PAYMENT);
        assertThat(handBacks(id, "REFUND_OUTCOME_UNCERTAIN")).isEqualTo(1);
        assertThat(count("select count(*) from billing_exception where payment_id = ?", refund(id).get("payment_id"))).as("B4: no second exception").isEqualTo(1);

        assertThat(refunds.execute(id)).as("nothing more happens automatically").isEqualTo(Outcome.NOTHING_TO_DO);
        assertThat(fakeEt.refundSubmitCalls()).isEqualTo(1);
    }

    @Test
    void anUnknownRefundWithoutALookupCapabilityIsHandedBackAfterTheWindow() {
        fakeEt.script(FakeProviderClient.Script.TIMEOUT_AFTER_ACCEPT);
        String id = lateRefund(owner);
        assertThatThrownBy(() -> refunds.execute(id)).isInstanceOf(TransientJobException.class);
        fakeEt.capabilities(new ProviderCapabilities(true, true, true, Duration.ofHours(24), false));

        later(Duration.ofHours(25));
        assertThat(refunds.execute(id)).isEqualTo(Outcome.HANDED_BACK);

        assertThat(fakeEt.refundSubmitCalls()).isEqualTo(1);
        assertThat(handBacks(id, "REFUND_OUTCOME_UNCERTAIN")).isEqualTo(1);
    }

    // ---- AC3, MT-04 c and d ----

    @Test
    void ac3CrashAfterSubmittingWithAnEmptyLookupIsNeverResubmitted() {
        String id = lateRefund(owner);
        crashAfterSubmitting(id);
        fakeEt.script(FakeProviderClient.Script.LOOKUP_LAG).laggedLookups(100);

        later(Duration.ofHours(25));
        assertThat(refunds.execute(id)).isEqualTo(Outcome.HANDED_BACK);

        assertThat(fakeEt.refundSubmitCalls()).as("an empty lookup proves nothing").isZero();
        assertThat(refund(id).get("status")).isEqualTo("UNKNOWN");
        assertThat(handBacks(id, "REFUND_OUTCOME_UNCERTAIN")).isEqualTo(1);
    }

    @Test
    void mt04dALookupThatFindsTheRefundRecordsItWithoutSubmitting() {
        fakeEt.script(FakeProviderClient.Script.TIMEOUT_AFTER_ACCEPT);
        String id = lateRefund(owner);
        assertThatThrownBy(() -> refunds.execute(id)).isInstanceOf(TransientJobException.class);
        fakeEt.script(FakeProviderClient.Script.SUCCESS);

        later(Duration.ofHours(25));
        assertThat(refunds.execute(id)).isEqualTo(Outcome.SUBMITTED);

        assertThat(fakeEt.refundSubmitCalls()).isEqualTo(1);
        assertThat((String) refund(id).get("provider_refund_ref")).startsWith("fake_refund_");
        assertThat(exceptionFor(id).get("status")).as("found is not settled").isEqualTo("AUTO_HANDLING");
        assertNeverSettled();
    }

    @Test
    void aCrashAfterSubmittingInsideTheWindowIsRetriedWithTheSameKey() {
        String id = lateRefund(owner);
        crashAfterSubmitting(id);

        later(Duration.ofHours(1));
        assertThat(refunds.execute(id)).isEqualTo(Outcome.SUBMITTED);

        assertThat(fakeEt.refundSubmitCalls()).isEqualTo(1);
        assertThat(fakeEt.lastRefundKeyFor(id)).isEqualTo("refund:" + id);
    }

    // ---- AC4, MT-04 e ----

    @Test
    void ac4ADefiniteRejectionIsHandedBackAndNeverRetried() {
        String id = lateRefund(owner);
        fakeEt.script(FakeProviderClient.Script.DECLINE);

        assertThat(refunds.execute(id)).isEqualTo(Outcome.FAILED_DEFINITE);

        assertThat(refund(id).get("status")).isEqualTo("FAILED_DEFINITE");
        assertThat(exceptionFor(id).get("status")).isEqualTo("OPEN");
        assertThat(handBacks(id, "REFUND_REJECTED")).isEqualTo(1);
        fakeEt.script(FakeProviderClient.Script.SUCCESS);
        assertThat(refunds.execute(id)).isEqualTo(Outcome.NOTHING_TO_DO);
        assertThat(fakeEt.refundSubmitCalls()).isEqualTo(1);
        assertThat(count("select count(*) from refund_request where payment_id = ?", refund(id).get("payment_id"))).isEqualTo(1);
    }

    // ---- AC5, MT-04 f, NC-26 ----

    @Test
    void ac5WithoutAConfirmedIdempotentRefundNothingIsSubmitted() {
        String id = lateRefund(owner);
        fakeEt.capabilities(ProviderCapabilities.none());

        assertThat(refunds.execute(id)).isEqualTo(Outcome.HANDED_BACK);

        assertThat(fakeEt.refundSubmitCalls()).isZero();
        assertThat(refund(id).get("status")).isEqualTo("REQUESTED");
        assertThat(exceptionFor(id).get("status")).isEqualTo("OPEN");
        assertThat(handBacks(id, "REFUND_REQUIRED_MANUAL")).isEqualTo(1);
    }

    @Test
    void anUnknownRefundIsNeverRetriedOnceTheCapabilityIsWithdrawn() {
        fakeEt.script(FakeProviderClient.Script.TIMEOUT_AFTER_ACCEPT);
        String id = lateRefund(owner);
        assertThatThrownBy(() -> refunds.execute(id)).isInstanceOf(TransientJobException.class);
        fakeEt.capabilities(new ProviderCapabilities(true, true, false, Duration.ofHours(24), true)).script(FakeProviderClient.Script.LOOKUP_LAG).laggedLookups(100);

        later(Duration.ofHours(1));
        assertThat(refunds.execute(id)).isEqualTo(Outcome.HANDED_BACK);

        assertThat(fakeEt.refundSubmitCalls()).isEqualTo(1);
    }

    // ---- durability and gates ----

    @Test
    void anUnknownRefundOnTheJobsLastRunIsHandedBackRatherThanLeftDead() {
        fakeEt.script(FakeProviderClient.Script.TIMEOUT_AFTER_ACCEPT);
        String id = lateRefund(owner);
        jdbc.update("update job set claims = 8, first_attempt_at = now() where type = 'refund.execute' and key = ?", id);

        assertThat(refunds.execute(id)).isEqualTo(Outcome.HANDED_BACK);

        assertThat(refund(id).get("status")).isEqualTo("UNKNOWN");
        assertThat(handBacks(id, "REFUND_OUTCOME_UNCERTAIN")).isEqualTo(1);
    }

    @Test
    void withTheMarketsBillingOffNothingIsSubmitted() {
        String id = lateRefund(owner);
        outcomes.setBillingEnabled(Market.ET, false);

        assertThatThrownBy(() -> refunds.execute(id)).isInstanceOf(PermanentJobException.class);

        assertThat(refund(id).get("status")).isEqualTo("REQUESTED");
        assertThat(fakeEt.refundSubmitCalls()).isZero();
    }

    @Test
    void twoWorkersExecutingOneRefundSubmitOnce() throws Exception {
        for (int round = 0; round < 6; round++) {
            Tenant t = data.tenant("refund-race-" + round);
            String id = lateRefund(t);
            int before = fakeEt.refundSubmitCalls();

            race(2, i -> () -> {
                try {
                    refunds.execute(id);
                } catch (RuntimeException ignored) {
                    // the loser may find the request mid-flight
                }
                return 0;
            });

            assertThat(fakeEt.refundSubmitCalls() - before).as("round %d", round).isEqualTo(1);
            assertThat(refund(id).get("status")).isEqualTo("SUBMITTED");
        }
    }

    @Test
    void submissionRefusesToRunInsideATransaction() {
        String id = lateRefund(owner);

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(s -> refunds.execute(id)))
                .isInstanceOf(com.agafari.com.jobs.ProviderCallInTransactionException.class);
        assertThat(refund(id).get("status")).isEqualTo("REQUESTED");
    }

    private void crashAfterSubmitting(String id) {
        jdbc.update("update refund_request set status = 'SUBMITTING', idempotency_key = ?, submitted_at = ? where id = ?", "refund:" + id,
                java.sql.Timestamp.from(clock.now()), id);
    }

    @SuppressWarnings("unused")
    private static void await(CountDownLatch l) throws InterruptedException {
        l.await(10, TimeUnit.SECONDS);
    }
}
