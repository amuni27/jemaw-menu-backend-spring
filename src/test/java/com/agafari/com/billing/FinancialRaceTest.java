package com.agafari.com.billing;

import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.dto.request.BillingExceptionActionRequest;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.subscription.PeriodEndJob;
import com.agafari.com.subscription.ScheduledChangeJob;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGF SUB-R4-16, races R1–R11 (assessment §5.1). Each race runs:
 * <ol>
 *   <li><b>every serial order, forced</b> by the harness's own latches (requirement 2); for three-part races all six orders;</li>
 *   <li><b>one contended run</b> behind a gate, released only once every attempt is proven waiting (lock evidence);</li>
 *   <li><b>20 barrier-released iterations</b> (test strategy §6), whose observed orders are tallied.</li>
 * </ol>
 * Every run asserts only committed state, and the invariants of the order it observed. A forced run must also observe the
 * outcome its order implies. Results go to {@code races.txt}; gate proofs to {@code lock-evidence.txt}.
 */
class FinancialRaceTest extends FinancialSuiteBase {

    private static final int STRESS = 20;

    @Autowired private RefundService refundService;
    @Autowired private ExceptionService exceptionService;
    @Autowired private PeriodEndJob periodEndJob;
    @Autowired private ScheduledChangeJob scheduledChangeJob;

    // ---- harness ----

    private static final class Round {
        final Tenant t;
        final Map<String, String> ids = new HashMap<>();
        final Map<String, Integer> counters = new HashMap<>();

        Round(Tenant t) {
            this.t = t;
        }

        String id(String key) {
            return ids.get(key);
        }
    }

    private record Attempt(String name, Function<Round, Callable<?>> body) {
    }

    private interface Setup {
        Round make(int n) throws Exception;
    }

    private interface Check {
        /** Asserts the invariants of what happened and returns the observed outcome label. */
        String verify(Round r, Map<String, Object> results) throws Exception;
    }

    private enum GateKind { CONFIG, RESTAURANT }

    private int round;

    /**
     * @param expected the outcome a forced order must observe, from the order of attempt names
     */
    private void race(String name, GateKind gate, Setup setup, List<Attempt> attempts, Check check, Function<List<String>, String> expected) throws Exception {
        Map<String, String> forcedResults = new LinkedHashMap<>();
        for (List<Attempt> order : permutations(attempts)) {
            Round r = setup.make(round++);
            List<String> names = order.stream().map(Attempt::name).toList();
            Map<String, Object> results = chain(order, r);
            String observed = check.verify(r, results);
            assertThat(observed).as("%s forced %s", name, names).isEqualTo(expected.apply(names));
            forcedResults.put(String.join(">", names), observed + " " + summary(results));
        }

        Round contendedRound = setup.make(round++);
        Gate g = gate == GateKind.CONFIG ? configGate() : restaurantGate(contendedRound.t);
        List<Callable<?>> bodies = new ArrayList<>();
        for (Attempt a : attempts) bodies.add(a.body().apply(contendedRound));
        List<Object> raw = contended(g, name, bodies);
        String contendedObserved = check.verify(contendedRound, named(attempts, raw));

        Map<String, Integer> tally = new TreeMap<>();
        for (int i = 0; i < STRESS; i++) {
            Round r = setup.make(round++);
            Map<String, Object> results = barrier(attempts, r);
            tally.merge(check.verify(r, results), 1, Integer::sum);
        }
        assertLedgerAccounted(name);
        evidence("races.txt", name + " | gate=" + gate + " | forced: " + forcedResults + " | contended observed=" + contendedObserved + " | stress " + STRESS + " observed: " + tally);
    }

    private static List<List<Attempt>> permutations(List<Attempt> in) {
        if (in.size() <= 1) return List.of(in);
        List<List<Attempt>> out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            List<Attempt> rest = new ArrayList<>(in);
            Attempt head = rest.remove(i);
            for (List<Attempt> p : permutations(rest)) {
                List<Attempt> l = new ArrayList<>();
                l.add(head);
                l.addAll(p);
                out.add(l);
            }
        }
        return out;
    }

    /** Forced serial order: each attempt waits on a latch the previous one releases after it returned. */
    private Map<String, Object> chain(List<Attempt> order, Round r) {
        List<Future<?>> futures = new ArrayList<>();
        CountDownLatch previous = null;
        for (Attempt a : order) {
            Callable<?> body = a.body().apply(r);
            CountDownLatch wait = previous, done = new CountDownLatch(1);
            futures.add(async(() -> {
                try {
                    if (wait != null && !wait.await(60, TimeUnit.SECONDS)) throw new AssertionError("previous attempt never finished");
                    return body.call();
                } finally {
                    done.countDown();
                }
            }));
            previous = done;
        }
        List<Object> raw = new ArrayList<>();
        for (Future<?> f : futures) raw.add(settle(f));
        return named(order, raw);
    }

    private Map<String, Object> barrier(List<Attempt> attempts, Round r) {
        CyclicBarrier start = new CyclicBarrier(attempts.size());
        List<Future<?>> futures = new ArrayList<>();
        for (Attempt a : attempts) {
            Callable<?> body = a.body().apply(r);
            futures.add(async(() -> {
                start.await(10, TimeUnit.SECONDS);
                return body.call();
            }));
        }
        List<Object> raw = new ArrayList<>();
        for (Future<?> f : futures) raw.add(settle(f));
        return named(attempts, raw);
    }

    private static Map<String, Object> named(List<Attempt> attempts, List<Object> raw) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < attempts.size(); i++) m.put(attempts.get(i).name(), raw.get(i));
        return m;
    }

    private static String summary(Map<String, Object> results) {
        Map<String, String> s = new LinkedHashMap<>();
        results.forEach((k, v) -> s.put(k, outcome(v)));
        return s.toString();
    }

    private static Function<List<String>, String> first(Map<String, String> byFirst) {
        return names -> byFirst.get(names.get(0));
    }

    /** The owner replaced this checkout: CANCEL_REQUESTED now, as the replacement API records it; returns t_c. */
    private Instant replaced(String checkoutId) {
        jdbc.update("update checkout_session set status = 'CANCEL_REQUESTED', cancel_requested_at = now(), cancelled_reason = 'OWNER_REPLACED', row_version = row_version + 1 where id = ?", checkoutId);
        return jdbc.queryForObject("select cancel_requested_at from checkout_session where id = ?", Timestamp.class, checkoutId).toInstant();
    }

    private Instant later() {
        return micros(Instant.now().plus(1, ChronoUnit.MINUTES));
    }

    // ---- R1: two payments of a replaced checkout (MT-01 timing V3/V4) ----

    @Test
    void r1TwoPaymentsOfAReplacedCheckoutFirstVerifiedPaymentWins() throws Exception {
        race("R1 payment A vs payment B (A paid before its cancel)", GateKind.CONFIG, n -> {
            Round r = new Round(data.tenant("r1-" + n));
            startOwnerFree(r.t);
            String free = current(r.t);
            String a = checkout(r.t, CheckoutKind.UPGRADE, free, price3, 1500, Instant.now().plus(23, ChronoUnit.HOURS));
            Instant tc = replaced(a);
            String b = checkout(r.t, CheckoutKind.UPGRADE, free, price, ET_AMOUNT, Instant.now().plus(23, ChronoUnit.HOURS));
            r.ids.put("free", free);
            r.ids.put("A", paid(a, tc.minusSeconds(1)));
            r.ids.put("B", paid(b, tc.plusSeconds(1)));
            r.ids.put("eA", event(r.id("A")));
            r.ids.put("eB", event(r.id("B")));
            return r;
        }, List.of(new Attempt("A", r -> () -> process(r.id("eA"))), new Attempt("B", r -> () -> process(r.id("eB")))), (r, results) -> {
            assertThat(currentRows(r.t)).isEqualTo(1);
            assertThat(periods(r.t)).isEqualTo(1);
            String consumed = jdbc.queryForObject("select p.provider_payment_ref from subscription_period sp join payment p on p.id = sp.payment_id where sp.business_id = ?", String.class, r.t.businessId());
            String winner = consumed.equals(r.id("A")) ? "A" : "B", loser = winner.equals("A") ? r.id("B") : r.id("A");
            assertThat(jdbc.queryForObject("select e.code from billing_exception e join payment p on p.id = e.payment_id where p.provider_payment_ref = ?", String.class, loser))
                    .isEqualTo(PaymentOutcome.STATE_CHANGED);
            assertThat(refunds(r.t)).as("row 6 never refunds").isZero();
            return winner + " wins";
        }, first(Map.of("A", "A wins", "B", "B wins")));
    }

    // ---- R2: webhook vs reconciliation for one payment ----

    @Test
    void r2WebhookAndReconciliationForOnePaymentRecordAndConsumeItOnce() throws Exception {
        race("R2 webhook vs reconciliation, one payment", GateKind.CONFIG, n -> {
            Round r = new Round(data.tenant("r2-" + n));
            String ref = paid(checkout(r.t, CheckoutKind.NEW, null), later());
            r.ids.put("ref", ref);
            r.ids.put("W", event(ref));
            r.ids.put("R", inbox.storeInternal(PROVIDER, ref));
            return r;
        }, List.of(new Attempt("W", r -> () -> process(r.id("W"))), new Attempt("R", r -> () -> process(r.id("R")))), (r, results) -> {
            assertThat(payments(r.t)).isEqualTo(1);
            assertThat(periods(r.t)).isEqualTo(1);
            assertThat(exceptions(r.t)).isZero();
            assertThat(eventRow(r.id("W")).get("status")).isEqualTo("PROCESSED");
            assertThat(eventRow(r.id("R")).get("status")).isEqualTo("PROCESSED");
            String verification = jdbc.queryForObject("select verification from payment where provider_payment_ref = ?", String.class, r.id("ref"));
            return "RECONCILIATION_API_FETCH".equals(verification) ? "R recorded" : "W recorded";
        }, first(Map.of("W", "W recorded", "R", "R recorded")));
    }

    // ---- R3: payment vs the owner's cancellation of its checkout ----

    @Test
    void r3PaymentVsOwnerCancelActivatesOrRefundsNeverBoth() throws Exception {
        race("R3 payment vs owner cancel", GateKind.CONFIG, n -> {
            Round r = new Round(data.tenant("r3-" + n));
            String c = checkout(r.t, CheckoutKind.NEW, null);
            r.ids.put("c", c);
            r.ids.put("e", event(paid(c, later())));
            return r;
        }, List.of(new Attempt("P", r -> () -> process(r.id("e"))), new Attempt("C", r -> () -> checkouts.cancel(r.t.businessId(), r.id("c")))), (r, results) -> {
            boolean cancelled = !(results.get("C") instanceof Throwable);
            if (cancelled) {
                assertThat(providerSubscriptions(r.t)).isZero();
                assertThat(jdbc.queryForObject("select code from billing_exception where business_id = ?", String.class, r.t.businessId())).isEqualTo(PaymentOutcome.PAID_AFTER_CANCEL);
                assertThat(refunds(r.t)).isEqualTo(1);
            } else {
                assertThat(providerSubscriptions(r.t)).isEqualTo(1);
                assertThat(exceptions(r.t)).isZero();
                assertThat(refunds(r.t)).isZero();
                assertThat(checkoutStatus(r.id("c"))).isEqualTo("COMPLETED");
            }
            return cancelled ? "cancel first" : "payment first";
        }, first(Map.of("P", "payment first", "C", "cancel first")));
    }

    // ---- R4: payment vs checkout expiry (reconciliation hourly) ----

    @Test
    void r4PaymentVsCheckoutExpiryIsJudgedByPaymentTime() throws Exception {
        race("R4 payment vs checkout expiry", GateKind.RESTAURANT, n -> {
            Round r = new Round(data.tenant("r4-" + n));
            Instant expires = micros(Instant.now().plus(30, ChronoUnit.MINUTES));
            String c = checkout(r.t, CheckoutKind.NEW, null, price, ET_AMOUNT, expires);
            r.ids.put("c", c);
            r.ids.put("e", event(paid(c, expires.minusSeconds(60)))); // paid before expiry
            return r;
        }, List.of(new Attempt("P", r -> () -> process(r.id("e"))),
                new Attempt("X", r -> () -> reconciliation.hourly(clock.now().plus(1, ChronoUnit.HOURS)))), (r, results) -> {
            assertThat(providerSubscriptions(r.t)).as("paid before expiry: activated in every order").isEqualTo(1);
            assertThat(periods(r.t)).isEqualTo(1);
            assertThat(exceptions(r.t)).isZero();
            String status = checkoutStatus(r.id("c"));
            assertThat(status).isIn("COMPLETED", "EXPIRED");
            return "EXPIRED".equals(status) ? "expiry first" : "payment first";
        }, first(Map.of("P", "payment first", "X", "expiry first")));
    }

    // ---- R5: payment vs an admin plan disable ----

    @Test
    void r5PaymentVsAdminPlanDisableFollowsTheConfigurationAtPayment() throws Exception {
        race("R5 payment vs admin plan disable", GateKind.CONFIG, n -> {
            Round r = new Round(data.tenant("r5-" + n));
            setModel4(true);
            String c = checkout(r.t, CheckoutKind.NEW, null);
            r.ids.put("e", event(paid(c, later())));
            return r;
        }, List.of(new Attempt("P", r -> () -> process(r.id("e"))), new Attempt("D", r -> () -> {
            new TransactionTemplate(transactions).executeWithoutResult(s -> {
                locks.configExclusive();
                setModel4(false);
            });
            return "disabled";
        })), (r, results) -> {
            boolean active = providerSubscriptions(r.t) == 1;
            boolean refused = exceptions(r.t) == 1;
            assertThat(active ^ refused).as("exactly one outcome").isTrue();
            if (refused) assertThat(jdbc.queryForObject("select code from billing_exception where business_id = ?", String.class, r.t.businessId())).isEqualTo(PaymentOutcome.PLAN_NOT_ELIGIBLE);
            return active ? "payment first" : "disable first";
        }, first(Map.of("P", "payment first", "D", "disable first")));
    }

    /** A new configuration version, in force now, enabling or disabling Model 4 (Models 2 and 3 stay on). */
    private void setModel4(boolean on) {
        int version = jdbc.queryForObject("select max(version) + 1 from monetization_config", Integer.class);
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) "
                + "values (?, 'SETTING_2', true, true, ?, ?, now(), ?)", version, on, Timestamp.from(Instant.now()), admin.getId());
    }

    // ---- R6: payment vs the owner's free-plan activation ----

    @Test
    void r6PaymentVsOwnerFreeActivationLeavesOneCurrentRow() throws Exception {
        race("R6 payment vs owner free activation", GateKind.CONFIG, n -> {
            Round r = new Round(data.tenant("r6-" + n));
            r.ids.put("e", event(paid(checkout(r.t, CheckoutKind.NEW, null), later())));
            return r;
        }, List.of(new Attempt("P", r -> () -> process(r.id("e"))), new Attempt("O", r -> () -> {
            startOwnerFree(r.t);
            return "owner";
        })), (r, results) -> {
            assertThat(currentRows(r.t)).isEqualTo(1);
            boolean ownerFirst = !(results.get("O") instanceof Throwable);
            if (ownerFirst) {
                assertThat(providerSubscriptions(r.t)).isZero();
                assertThat(jdbc.queryForObject("select code from billing_exception where business_id = ?", String.class, r.t.businessId())).isEqualTo(PaymentOutcome.STATE_CHANGED);
                assertThat(refunds(r.t)).as("BD-17").isZero();
            } else {
                assertThat(providerSubscriptions(r.t)).isEqualTo(1);
                assertThat(exceptions(r.t)).isZero();
            }
            return ownerFirst ? "owner first" : "payment first";
        }, first(Map.of("P", "payment first", "O", "owner first")));
    }

    // ---- R7: two executions of one refund request ----

    @Test
    void r7TwoRefundExecutionsSubmitOnce() throws Exception {
        race("R7 two refund executions", GateKind.RESTAURANT, n -> {
            Round r = new Round(data.tenant("r7-" + n));
            String c = checkout(r.t, CheckoutKind.NEW, null);
            checkouts.cancel(r.t.businessId(), c);
            process(event(paid(c, later()))); // row 4: an automatic refund request
            r.ids.put("refund", jdbc.queryForObject("select r.id from refund_request r join payment p on p.id = r.payment_id where p.business_id = ?", String.class, r.t.businessId()));
            r.counters.put("submits", fakeEt.refundSubmitCalls());
            return r;
        }, List.of(new Attempt("X", r -> () -> refundService.execute(r.id("refund"))), new Attempt("Y", r -> () -> refundService.execute(r.id("refund")))), (r, results) -> {
            assertThat(fakeEt.refundSubmitCalls() - r.counters.get("submits")).as("one provider submission").isEqualTo(1);
            assertThat(jdbc.queryForObject("select status from refund_request where id = ?", String.class, r.id("refund"))).isEqualTo("SUBMITTED");
            List<String> outcomes = results.values().stream().map(String::valueOf).sorted().toList();
            assertThat(outcomes).as("one attempt submitted, the other did nothing").containsExactly("NOTHING_TO_DO", "SUBMITTED");
            return results.get("X").toString().equals("SUBMITTED") ? "X submitted" : "Y submitted";
        }, first(Map.of("X", "X submitted", "Y", "Y submitted")));
    }

    // ---- R8: two admins resolving one exception ----

    @Test
    void r8TwoAdminsResolvingOneExceptionResolveItOnce() throws Exception {
        User second = data.admin("r8-second");
        race("R8 two admins resolve one exception", GateKind.RESTAURANT, n -> {
            Round r = new Round(data.tenant("r8-" + n));
            startOwnerFree(r.t);
            process(event(paid(checkout(r.t, CheckoutKind.NEW, null), later()))); // row 6
            String id = jdbc.queryForObject("select id from billing_exception where business_id = ?", String.class, r.t.businessId());
            r.ids.put("ex", id);
            r.counters.put("version", jdbc.queryForObject("select row_version from billing_exception where id = ?", Integer.class, id));
            return r;
        }, List.of(new Attempt("X", r -> () -> resolve(r, admin)), new Attempt("Y", r -> () -> resolve(r, second))), (r, results) -> {
            assertThat(jdbc.queryForObject("select status from billing_exception where id = ?", String.class, r.id("ex"))).isEqualTo("RESOLVED");
            long ok = results.values().stream().filter(v -> !(v instanceof Throwable)).count();
            assertThat(ok).as("exactly one resolution").isEqualTo(1);
            assertThat(count("select count(*) from admin_audit_event where target_id = ? and outcome = 'SUCCESS' and detail like '%RESOLVED%'", r.id("ex"))).as("one resolution audit").isEqualTo(1);
            return results.get("X") instanceof Throwable ? "Y resolved" : "X resolved";
        }, first(Map.of("X", "X resolved", "Y", "Y resolved")));
    }

    private Object resolve(Round r, User who) {
        return exceptionService.resolve(r.id("ex"), new BillingExceptionActionRequest(r.counters.get("version"), "handled by bank transfer", null, null, "FUNDS_HANDLED_EXTERNALLY"),
                who.getId(), who.getEmail());
    }

    // ---- R9: a funded downgrade at its boundary: period-end job vs change job vs a lifecycle command ----

    @Test
    void r9AFundedDowngradeIsAppliedOnceWhicheverBoundaryActorRunsFirst() throws Exception {
        evaluator.setPeriodEndEnabled(true);
        race("R9 funded downgrade: period-end job vs change job vs lifecycle command", GateKind.CONFIG, n -> {
            clock.reset();
            Round r = new Round(data.tenant("r9-" + n));
            String sub = paidRow(r.t, Duration.ofSeconds(30));
            String change = paidDowngrade(r.t, sub);
            String co = downgradeCheckout(r.t, change, sub);
            assertThat(process(event(paid(co, micros(Instant.now()))))).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
            assertThat(changeRow(change).get("funding_payment_id")).as("funded before the boundary").isNotNull();
            r.ids.put("sub", sub);
            r.ids.put("change", change);
            clock.setOffset(Duration.ofMinutes(2)); // the boundary has passed
            return r;
        }, boundaryActors(), (r, results) -> {
            assertFundedApplied(r);
            return "APPLIED";
        }, names -> "APPLIED");
    }

    private List<Attempt> boundaryActors() {
        return List.of(
                new Attempt("P", r -> () -> {
                    periodEndJob.handle(new PeriodEndJob.Payload(r.t.businessId(), r.id("sub"), periodEnd(r.id("sub"))));
                    return "period-end";
                }),
                new Attempt("C", r -> () -> {
                    scheduledChangeJob.handle(new ScheduledChangeJob.Payload(r.id("change")));
                    return "change-job";
                }),
                new Attempt("L", r -> () -> {
                    new TransactionTemplate(transactions).executeWithoutResult(s -> {
                        locks.configShared();
                        locks.restaurant(r.t.businessId());
                        lifecycle.materialize(r.t.businessId());
                    });
                    return "command";
                }));
    }

    private void assertFundedApplied(Round r) {
        Map<String, Object> change = changeRow(r.id("change"));
        assertThat(change.get("status")).as("funded: applied in every order, never failed by job order").isEqualTo("APPLIED");
        String target = (String) change.get("applied_subscription_id");
        Instant boundary = ((Timestamp) change.get("effective_at")).toInstant();
        assertThat(subRow(r.id("sub"))).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "CHANGED").containsEntry("replaced_by_id", target);
        assertThat(subRow(target)).containsEntry("plan_code", "MODEL_3").containsEntry("source", "PROVIDER");
        assertThat(currentRows(r.t)).isEqualTo(1);
        assertThat(count("select count(*) from subscription_period where subscription_id = ?", target)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select period_start from subscription_period where subscription_id = ?", Timestamp.class, target).toInstant()).isEqualTo(boundary);
        assertThat(count("select count(*) from admin_audit_event where target_id = ? and action = 'SCHEDULED_CHANGE_APPLIED'", r.id("change"))).as("applied once").isEqualTo(1);
        assertThat(exceptions(r.t)).isZero();
    }

    // ---- R9b: an on-time downgrade payment notified after the boundary vs the two boundary jobs ----

    @Test
    void r9bAnOnTimeFundingSeenAtTheBoundaryAppliesOnlyIfItIsProcessedBeforeExpiry() throws Exception {
        evaluator.setPeriodEndEnabled(true);
        List<Attempt> actors = new ArrayList<>();
        actors.add(new Attempt("F", r -> () -> process(r.id("event"))));
        actors.addAll(boundaryActors().subList(0, 2));
        race("R9b on-time funding seen late: funding vs period-end job vs change job", GateKind.CONFIG, n -> {
            clock.reset();
            Round r = new Round(data.tenant("r9b-" + n));
            String sub = paidRow(r.t, Duration.ofSeconds(30));
            String change = paidDowngrade(r.t, sub);
            String co = downgradeCheckout(r.t, change, sub);
            r.ids.put("sub", sub);
            r.ids.put("change", change);
            r.ids.put("event", event(paid(co, periodEnd(sub).minusSeconds(1)))); // paid on time, not yet processed
            clock.setOffset(Duration.ofMinutes(2));
            return r;
        }, actors, (r, results) -> {
            String status = (String) changeRow(r.id("change")).get("status");
            if ("APPLIED".equals(status)) {
                assertFundedApplied(r);
                return "funding first: APPLIED";
            }
            // expiry evaluated first: the change failed; the on-time payment goes to STATE_CHANGED review (P3), never row 7
            assertThat(status).isEqualTo("FAILED");
            assertThat(changeRow(r.id("change")).get("funding_payment_id")).isNull();
            assertThat(subRow(r.id("sub"))).containsEntry("status", "EXPIRED");
            assertThat(currentRows(r.t)).isZero();
            assertThat(count("select count(*) from business_subscription where business_id = ? and plan_code = 'MODEL_3'", r.t.businessId())).as("never activated").isZero();
            assertThat(jdbc.queryForObject("select code from billing_exception where business_id = ?", String.class, r.t.businessId())).isEqualTo(PaymentOutcome.STATE_CHANGED);
            assertThat(refunds(r.t)).isZero();
            return "expiry first: FAILED + review";
        }, names -> names.get(0).equals("F") ? "funding first: APPLIED" : "expiry first: FAILED + review");
    }

    // ---- R10: an activation superseding a change vs the funding of that change ----

    @Test
    void r10SupersessionVsFundingNeverAppliesTheChangeAndReviewsItsCharge() throws Exception {
        race("R10 upgrade superseding a change vs funding of that change", GateKind.CONFIG, n -> {
            clock.reset();
            Round r = new Round(data.tenant("r10-" + n));
            String sub = paidRow(r.t, Duration.ofHours(1));
            String change = paidDowngrade(r.t, sub);
            String co = downgradeCheckout(r.t, change, sub);
            Instant tc = replaced(co); // the owner replaced the downgrade checkout with an upgrade; the downgrade was paid just before
            String price4b = price("MODEL_4", "ET", "ETB", 3000 + n);
            String upgrade = checkout(r.t, CheckoutKind.UPGRADE, sub, price4b, 3000 + n, Instant.now().plus(23, ChronoUnit.HOURS));
            r.ids.put("sub", sub);
            r.ids.put("change", change);
            r.ids.put("F", event(paid(co, tc.minusSeconds(1))));
            r.ids.put("U", event(paid(upgrade, tc.plusSeconds(1))));
            r.ids.put("upgrade", upgrade);
            return r;
        }, List.of(new Attempt("F", r -> () -> process(r.id("F"))), new Attempt("U", r -> () -> process(r.id("U")))), (r, results) -> {
            Map<String, Object> change = changeRow(r.id("change"));
            assertThat(change.get("status")).isEqualTo("SUPERSEDED");
            assertThat(change.get("applied_subscription_id")).isNull();
            assertThat(currentRows(r.t)).isEqualTo(1);
            assertThat(count("select count(*) from business_subscription where business_id = ? and plan_code = 'MODEL_3'", r.t.businessId())).as("the change is never applied").isZero();
            assertThat(checkoutStatus(r.id("upgrade"))).isEqualTo("COMPLETED");
            String downgradeCharge = jdbc.queryForObject("select p.id from payment p join checkout_session c on c.id = p.checkout_session_id where c.kind = 'DOWNGRADE_RENEWAL' and p.business_id = ?",
                    String.class, r.t.businessId());
            assertThat(jdbc.queryForObject("select code from billing_exception where payment_id = ?", String.class, downgradeCharge)).as("its charge goes to review").isEqualTo(PaymentOutcome.STATE_CHANGED);
            assertThat(refunds(r.t)).isZero();
            return change.get("funding_payment_id") != null ? "funded, then superseded and released" : "superseded, then refused";
        }, first(Map.of("F", "funded, then superseded and released", "U", "superseded, then refused")));
    }

    // ---- R11: two charges for one renewal ----

    @Test
    void r11DuplicateRenewalChargesExtendOnce() throws Exception {
        race("R11 duplicate renewal charges", GateKind.CONFIG, n -> {
            Round r = new Round(data.tenant("r11-" + n));
            String sub = paidRow(r.t, Duration.ofDays(3));
            String renewal = checkout(r.t, CheckoutKind.RENEWAL, sub, price, ET_AMOUNT, Instant.now().plus(23, ChronoUnit.HOURS));
            r.ids.put("sub", sub);
            r.ids.put("end", periodEnd(sub).toString());
            r.ids.put("X", paid(renewal, later()));
            r.ids.put("Y", paid(renewal, later().plusSeconds(1)));
            r.ids.put("eX", event(r.id("X")));
            r.ids.put("eY", event(r.id("Y")));
            return r;
        }, List.of(new Attempt("X", r -> () -> process(r.id("eX"))), new Attempt("Y", r -> () -> process(r.id("eY")))), (r, results) -> {
            assertThat(periods(r.t)).as("the original period and one extension").isEqualTo(2);
            assertThat(periodEnd(r.id("sub"))).as("extended exactly one interval").isEqualTo(oneMonth(Instant.parse(r.id("end"))));
            String extra = jdbc.queryForObject("select p.provider_payment_ref from payment p join billing_exception e on e.payment_id = p.id where p.business_id = ?", String.class, r.t.businessId());
            assertThat(jdbc.queryForObject("select code from billing_exception where business_id = ?", String.class, r.t.businessId())).isEqualTo(PaymentOutcome.DUPLICATE_PAYMENT);
            assertThat(refunds(r.t)).as("the extra charge: an automatic full refund request (AD-9 open: accounting only)").isEqualTo(1);
            return extra.equals(r.id("X")) ? "Y extended" : "X extended";
        }, first(Map.of("X", "X extended", "Y", "Y extended")));
    }
}
