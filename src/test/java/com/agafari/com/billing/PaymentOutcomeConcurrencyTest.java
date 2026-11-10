package com.agafari.com.billing;

import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF SUB-R4-10: the story's races, each run several rounds on PostgreSQL. Whichever side commits first, the outcome is one
 * of the two legitimate ones, never both and never neither, and every charge is accounted for once (AC6).
 */
class PaymentOutcomeConcurrencyTest extends PaymentOutcomeTestBase {

    private static final int ROUNDS = 6;
    private static final int OK = 1, REFUSED = 0;

    private Instant later() {
        return micros(Instant.now().plus(1, ChronoUnit.MINUTES));
    }

    @Test
    void aWebhookRacingACancelRequestEitherActivatesOrRefundsNeverBoth() throws Exception {
        int activated = 0, refunded = 0;
        for (int round = 0; round < ROUNDS; round++) {
            Tenant t = data.tenant("race-cancel-" + round);
            String c = checkout(t, CheckoutKind.NEW, null);
            String e = event(paid(c, later()));
            Runnable wait = pace(round);

            List<Integer> r = race(2, i -> i == 0 ? () -> { process(e); return OK; } : () -> {
                wait.run();
                try {
                    checkouts.cancel(t.businessId(), c);
                    return OK;
                } catch (RuntimeException refused) {
                    return REFUSED;
                }
            });

            boolean cancelled = r.get(1) == OK;
            if (cancelled) {
                assertThat(providerSubscriptions(t)).isZero();
                assertThat(jdbc.queryForObject("select code from billing_exception where business_id = ?", String.class, t.businessId())).isEqualTo(PaymentOutcome.PAID_AFTER_CANCEL);
                assertThat(refunds(t)).isEqualTo(1);
                refunded++;
            } else {
                assertThat(providerSubscriptions(t)).isEqualTo(1);
                assertThat(exceptions(t)).isZero();
                assertThat(checkoutStatus(c)).isEqualTo("COMPLETED");
                activated++;
            }
            assertThat(eventRow(e).get("status")).isEqualTo("PROCESSED");
        }
        assertThat(activated + refunded).isEqualTo(ROUNDS);
        assertThat(activated).as("the payment won some rounds").isPositive();
        assertThat(refunded).as("the cancel won some rounds").isPositive();
        System.out.println("race-report cancel: activated=" + activated + " refunded=" + refunded);
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void aWebhookRacingAnAdminPlanDisableFollowsTheConfigurationInForceAtPayment() throws Exception {
        int activatedRounds = 0;
        for (int round = 0; round < ROUNDS; round++) {
            Tenant t = data.tenant("race-disable-" + round);
            setModel4(true);
            String c = checkout(t, CheckoutKind.NEW, null);
            String e = event(paid(c, later()));
            Runnable wait = pace(round);

            race(2, i -> i == 0 ? () -> { process(e); return OK; } : () -> {
                wait.run();
                new TransactionTemplate(transactions).executeWithoutResult(s -> {
                    locks.configExclusive();
                    setModel4(false);
                });
                return OK;
            });

            boolean active = providerSubscriptions(t) == 1;
            boolean refused = exceptions(t) == 1;
            assertThat(active ^ refused).as("round %d: exactly one outcome", round).isTrue();
            System.out.println("race-report disable: round=" + round + " " + (active ? "activated" : "refused"));
            if (active) activatedRounds++;
            if (refused) {
                assertThat(jdbc.queryForObject("select code from billing_exception where business_id = ?", String.class, t.businessId())).isEqualTo(PaymentOutcome.PLAN_NOT_ELIGIBLE);
            }
        }
        assertThat(activatedRounds).as("both orders occurred").isBetween(1, ROUNDS - 1);
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void aWebhookRacingAnOwnersFreePlanActivationLeavesOneCurrentRow() throws Exception {
        int ownerFirst = 0;
        for (int round = 0; round < ROUNDS; round++) {
            Tenant t = data.tenant("race-owner-" + round);
            String c = checkout(t, CheckoutKind.NEW, null);
            String e = event(paid(c, later()));
            Runnable wait = pace(round);

            List<Integer> r = race(2, i -> i == 0 ? () -> { process(e); return OK; } : () -> {
                wait.run();
                try {
                    startOwnerFree(t);
                    return OK;
                } catch (RuntimeException refused) {
                    return REFUSED;
                }
            });

            assertThat(count("select count(*) from business_subscription where business_id = ? and status <> 'EXPIRED'", t.businessId())).isEqualTo(1);
            System.out.println("race-report owner: round=" + round + " " + (r.get(1) == OK ? "owner-first" : "payment-first"));
            if (r.get(1) == OK) {
                ownerFirst++;
                assertThat(providerSubscriptions(t)).isZero();
                assertThat(jdbc.queryForObject("select code from billing_exception where business_id = ?", String.class, t.businessId())).isEqualTo(PaymentOutcome.STATE_CHANGED);
                assertThat(refunds(t)).as("BD-17").isZero();
            } else {
                assertThat(providerSubscriptions(t)).isEqualTo(1);
                assertThat(exceptions(t)).isZero();
            }
        }
        assertThat(ownerFirst).as("both orders occurred").isBetween(1, ROUNDS - 1);
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void twoEventsForOnePaymentProcessedTogetherRecordAndConsumeItOnce() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Tenant t = data.tenant("race-same-payment-" + round);
            String ref = paid(checkout(t, CheckoutKind.NEW, null), later());
            String a = event(ref), b = event(ref);

            race(2, i -> () -> { process(i == 0 ? a : b); return OK; });

            assertThat(payments(t)).isEqualTo(1);
            assertThat(periods(t)).isEqualTo(1);
            assertThat(exceptions(t)).isZero();
            assertThat(eventRow(a).get("status")).isEqualTo("PROCESSED");
            assertThat(eventRow(b).get("status")).isEqualTo("PROCESSED");
        }
        assertEveryChargeAccountedForOnce();
    }

    @Test
    void oneEventProcessedTwiceAtOnceHasOneOutcome() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            Tenant t = data.tenant("race-same-event-" + round);
            String e = event(paid(checkout(t, CheckoutKind.NEW, null), later()));

            race(2, i -> () -> { process(e); return OK; });

            assertThat(payments(t)).isEqualTo(1);
            assertThat(periods(t)).isEqualTo(1);
            assertThat(eventRow(e).get("status")).isEqualTo("PROCESSED");
        }
        assertEveryChargeAccountedForOnce();
    }

    /**
     * Even rounds hold the other side until the payment's provider re-fetch has started, so the two arrive at the locks
     * together and the payment can win; odd rounds start both at once, where the other side usually wins.
     */
    private Runnable pace(int round) {
        CountDownLatch fetched = new CountDownLatch(1);
        fakeEt.onVerify(fetched::countDown);
        return () -> {
            if (round % 2 != 0) return;
            try {
                fetched.await(10, TimeUnit.SECONDS);
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    /** A new configuration version, in force now, enabling or disabling MODEL_4 (MODEL_2 stays on). */
    private void setModel4(boolean on) {
        int version = jdbc.queryForObject("select max(version) + 1 from monetization_config", Integer.class);
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) "
                + "values (?, 'SETTING_2', true, false, ?, ?, now(), ?)", version, on, Timestamp.from(Instant.now()), admin.getId());
    }
}
