package com.agafari.com.subscription;

import com.agafari.com.billing.TestEvidence;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.subscription.EvidenceInvalidException.Code;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AGF SUB-R4-09: paid commands against PostgreSQL. Amounts are fixtures, not prices (AD-10 is open). */
class PaidLifecycleTest extends AbstractIntegrationTest {

    private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2030-02-01T00:00:00Z");
    private static final Instant NEXT = Instant.parse("2030-03-01T00:00:00Z");
    private static final String VERIFIED = "SIGNED_WEBHOOK+API_FETCH";

    @Autowired private SubscriptionLifecycle lifecycle;
    @Autowired private MonetizationLocks locks;
    @Autowired private MonetizationClock clock;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private User admin;
    private Tenant owner;
    private Tenant other;
    private String price;
    private String otherPlanPrice;

    private static String lit(String v) {
        return "'" + v + "'";
    }

    private static String ts(Instant i) {
        return lit(i.toString()) + "::timestamptz";
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static Map<String, String> m(String... kv) {
        Map<String, String> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) r.put(kv[i], kv[i + 1]);
        return r;
    }

    private String insert(String table, Map<String, String> defaults, Map<String, String> o) {
        Map<String, String> row = new LinkedHashMap<>(m("id", lit(id())));
        row.putAll(defaults);
        row.putAll(o);
        jdbc.update("insert into " + table + " (" + String.join(", ", row.keySet()) + ") values (" + String.join(", ", row.values()) + ")");
        return row.get("id").replace("'", "");
    }

    @BeforeEach
    void setUp() {
        clock.reset();
        admin = data.admin("paid-admin");
        owner = data.tenant("paid-owner");
        other = data.tenant("paid-other");
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        price = priceFor("MODEL_4", "ET", "ETB", 1000);
        otherPlanPrice = priceFor("MODEL_3", "ET", "ETB", 1000);
        account(owner, "ET", "P1");
        account(other, "ET", "P1");
    }

    private int version;

    private String priceFor(String plan, String market, String currency, long amount) {
        String pid = id();
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, active, valid_from) values (?, ?, ?, ?, 'MONTH', ?, ?, true, now())", pid, plan, market, currency, amount, ++version);
        return pid;
    }

    private void account(Tenant t, String market, String provider) {
        jdbc.update("insert into billing_account (id, business_id, market, provider) values (?, ?, ?, ?)", id(), t.businessId(), market, provider);
    }

    private String invoice(Map<String, String> o) {
        return insert("billing_invoice", m("business_id", lit(owner.businessId()), "price_id", lit(price), "period_start", ts(START), "period_end", ts(END), "amount_minor", "1000", "currency", "'ETB'", "status", "'PAID'", "provider_invoice_ref", lit(id())), o);
    }

    private String payment(String invoice, Map<String, String> o) {
        return insert("payment", m("business_id", lit(owner.businessId()), "invoice_id", invoice == null ? "null" : lit(invoice), "provider", "'P1'", "provider_payment_ref", lit(id()), "amount_minor", "1000", "currency", "'ETB'",
                "kind", "'CHARGE'", "status", "'SUCCEEDED'", "occurred_at", "now()", "verified_at", "now()", "verification", lit(VERIFIED)), o);
    }

    private record Ev(String payment, String invoice) {
    }

    private Ev evidence() {
        return evidence(m(), m());
    }

    private Ev evidence(Map<String, String> onPayment, Map<String, String> onInvoice) {
        String i = invoice(onInvoice);
        return new Ev(payment(i, onPayment), i);
    }

    private Ev renewalEvidence() {
        return evidence(m(), m("period_start", ts(END), "period_end", ts(NEXT)));
    }

    private PaidActivation activation(Ev e) {
        return activation(e, "MODEL_4", "P1", START, END);
    }

    private PaidActivation activation(Ev e, String plan, String provider, Instant start, Instant end) {
        return new PaidActivation(plan, 1, price, provider, null, null, start, end, TestEvidence.ref(e.payment(), e.invoice()), Instant.now());
    }

    private <T> T locked(Tenant t, Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(s -> {
            locks.configShared();
            locks.restaurant(t.businessId());
            return work.get();
        });
    }

    private BusinessSubscription activate(Tenant t, PaidActivation a) {
        return locked(t, () -> lifecycle.activatePaid(t.businessId(), a));
    }

    private BusinessSubscription active() {
        return activate(owner, activation(evidence()));
    }

    private BusinessSubscription renew(BusinessSubscription s, Ev e, Instant start, Instant end) {
        return locked(owner, () -> lifecycle.renew(s.getId(), new PeriodExtension(start, end, TestEvidence.ref(e.payment(), e.invoice()), Instant.now())));
    }

    private int periods() {
        return jdbc.queryForObject("select count(*) from subscription_period", Integer.class);
    }

    private int subscriptionsOf(Tenant t) {
        return jdbc.queryForObject("select count(*) from business_subscription where business_id = ?", Integer.class, t.businessId());
    }

    private String manual() {
        return locked(owner, () -> lifecycle.recordManual(owner.businessId(), new ManualRecord("MODEL_4", 1, END, Instant.now()), new Actor(admin.getId()))).getId();
    }

    private static void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Code code) {
        assertThatThrownBy(call).isInstanceOf(EvidenceInvalidException.class).satisfies(e -> assertThat(((EvidenceInvalidException) e).code()).isEqualTo(code));
    }

    // ---- activation

    @Test
    void aVerifiedPaymentActivatesAProviderRowWithItsPeriod() {
        BusinessSubscription s = active();
        Map<String, Object> row = jdbc.queryForMap("select * from business_subscription where id = ?", s.getId());
        assertThat(row).containsEntry("source", "PROVIDER").containsEntry("status", "ACTIVE").containsEntry("plan_code", "MODEL_4").containsEntry("provider", "P1").containsEntry("price_id", price).containsEntry("grace_days", null);
        assertThat(periods()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = 'SUBSCRIPTION_PAID_ACTIVATED' and target_id = ?", Integer.class, s.getId())).isEqualTo(1);
    }

    @Test
    void activationRefusesWhileAnotherSubscriptionIsCurrent() {
        manual();
        assertThatThrownBy(() -> activate(owner, activation(evidence()))).isInstanceOf(ExpectedSubscriptionChangedException.class);
        assertThat(periods()).isZero();
    }

    @Test
    void everyPaidCommandNeedsTheLocks() {
        Ev e = evidence();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(s -> lifecycle.activatePaid(owner.businessId(), activation(e)))).isInstanceOf(IllegalStateException.class);
        BusinessSubscription s = active();
        Ev next = renewalEvidence();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(x -> lifecycle.renew(s.getId(), new PeriodExtension(END, NEXT, TestEvidence.ref(next.payment(), next.invoice()), Instant.now())))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(x -> lifecycle.markPastDue(s.getId(), Instant.now(), Instant.now()))).isInstanceOf(IllegalStateException.class);
        Ev third = evidence(m(), m("period_start", ts(END), "period_end", ts(NEXT)));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(x -> lifecycle.replacePaid(owner.businessId(), s.getId(), activation(third, "MODEL_4", "P1", END, NEXT), TransitionReason.CHANGED))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> locked(other, () -> lifecycle.markPastDue(s.getId(), Instant.now(), Instant.now()))).isInstanceOf(IllegalStateException.class);
    }

    // ---- every evidence refusal: by the application, and the same case by SQL

    private record Case(String name, Map<String, String> onPayment, Map<String, String> onInvoice, Code code, boolean alsoRefusedByDatabase) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> evidenceCases() {
        return Stream.of(
                new Case("a refund", m("kind", "'REFUND'"), m(), Code.NOT_VERIFIED, true),
                new Case("a chargeback", m("kind", "'CHARGEBACK'"), m(), Code.NOT_VERIFIED, true),
                new Case("invoice open", m(), m("status", "'OPEN'"), Code.NOT_VERIFIED, true),
                new Case("invoice void", m(), m("status", "'VOID'"), Code.NOT_VERIFIED, true),
                new Case("invoice refunded", m(), m("status", "'REFUNDED'"), Code.NOT_VERIFIED, true),
                new Case("payment of another restaurant", m("business_id", "$OTHER"), m(), Code.OWNER_MISMATCH, true),
                new Case("invoice of another restaurant", m(), m("business_id", "$OTHER"), Code.OWNER_MISMATCH, true),
                new Case("payment of another provider", m("provider", "'P2'"), m(), Code.OWNER_MISMATCH, true),
                new Case("amount differs from the invoice", m("amount_minor", "999"), m(), Code.AMOUNT_MISMATCH, true),
                new Case("currency differs from the invoice", m("currency", "'USD'"), m(), Code.AMOUNT_MISMATCH, true),
                new Case("invoice differs from the price", m("amount_minor", "999"), m("amount_minor", "999"), Code.AMOUNT_MISMATCH, false),
                new Case("invoice for another plan", m(), m("price_id", "$OTHER_PLAN"), Code.PLAN_MISMATCH, true),
                new Case("period start differs", m(), m("period_start", "'2030-01-02T00:00:00Z'::timestamptz"), Code.PERIOD_MISMATCH, true),
                new Case("period end differs", m(), m("period_end", "'2030-02-02T00:00:00Z'::timestamptz"), Code.PERIOD_MISMATCH, true));
    }

    private Map<String, String> bind(Map<String, String> o) {
        Map<String, String> r = new LinkedHashMap<>();
        o.forEach((k, v) -> r.put(k, switch (v) {
            case "$OTHER" -> lit(other.businessId());
            case "$OTHER_PLAN" -> lit(otherPlanPrice);
            default -> v;
        }));
        return r;
    }

    /** AGF V23.4: charges that can no longer exist. PostgreSQL checks constraints in name order. */
    static Stream<Arguments> invalidChargeRecords() {
        String charge = "ck_payment_charge_verified", success = "ck_payment_success_evidence";
        return Stream.of(
                Arguments.of("payment pending", m("status", "'PENDING'"), charge),
                Arguments.of("payment failed", m("status", "'FAILED'"), charge),
                Arguments.of("SUCCEEDED but unverified", m("verified_at", "null", "verification", "null"), charge),
                Arguments.of("no verified_at", m("verified_at", "null"), success),
                Arguments.of("no verification", m("verification", "null"), charge),
                Arguments.of("unknown verification", m("verification", "'TRUST_ME'"), charge));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidChargeRecords")
    void anInvalidChargeIsRefusedAtInsert(String name, Map<String, String> onPayment, String constraint) {
        assertThatThrownBy(() -> evidence(onPayment, m())).hasMessageContaining(constraint);
        assertThat(subscriptionsOf(owner)).isZero();
        assertThat(periods()).isZero();
    }

    /** The application check stays a separate layer: with V23.4's constraints removed inside a rolled-back transaction, it still refuses. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidChargeRecords")
    void theValidatorStillRefusesAnInvalidChargeOnItsOwn(String name, Map<String, String> onPayment, String constraint) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            jdbc.execute("alter table payment drop constraint ck_payment_success_evidence, drop constraint ck_payment_charge_verified");
            Ev e = evidence(onPayment, m());
            refused(() -> activate(owner, activation(e)), Code.NOT_VERIFIED);
            assertThat(subscriptionsOf(owner)).isZero();
            assertThat(periods()).isZero();
            s.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conname in ('ck_payment_success_evidence', 'ck_payment_charge_verified')", Integer.class)).isEqualTo(2);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("evidenceCases")
    void forgedEvidenceIsRefusedByTheApplication(Case c) {
        Ev e = evidence(bind(c.onPayment()), bind(c.onInvoice()));
        refused(() -> activate(owner, activation(e)), c.code());
        assertThat(subscriptionsOf(owner)).isZero();
        assertThat(periods()).isZero();
    }

    /** The one exception, invoice against plan_price, is an application rule: V23.2 doesn't compare them. */
    static Stream<Case> databaseCases() {
        return evidenceCases().filter(Case::alsoRefusedByDatabase);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databaseCases")
    void theSameCaseWrittenBySqlIsRefusedByTheDatabase(Case c) {
        Ev e = evidence(bind(c.onPayment()), bind(c.onInvoice()));
        assertThatThrownBy(() -> insertBySql(owner, e, "MODEL_4", START, END)).isInstanceOf(Exception.class);
        assertThat(subscriptionsOf(owner)).isZero();
        assertThat(periods()).isZero();
    }

    private void insertBySql(Tenant t, Ev e, String plan, Instant start, Instant end) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            String sub = insert("business_subscription", m("business_id", lit(t.businessId()), "plan_code", lit(plan), "status", "'ACTIVE'", "config_version", "1", "period_end", ts(end), "source", "'PROVIDER'", "provider", "'P1'", "price_id", lit(price),
                    "created_at", "now()", "created_by_user_id", lit(admin.getId()), "updated_at", "now()", "updated_by_user_id", lit(admin.getId()), "row_version", "0"), m());
            insert("subscription_period", m("subscription_id", lit(sub), "business_id", lit(t.businessId()), "period_start", ts(start), "period_end", ts(end), "invoice_id", lit(e.invoice()), "payment_id", lit(e.payment())), m());
        });
    }

    @Test
    void notFoundEvidenceIsRefusedByBoth() {
        Ev real = evidence();
        for (Ev missing : List.of(new Ev("no-such", real.invoice()), new Ev(real.payment(), "no-such"))) {
            refused(() -> activate(owner, activation(missing)), Code.NOT_FOUND);
            assertThatThrownBy(() -> insertBySql(owner, missing, "MODEL_4", START, END)).isInstanceOf(Exception.class);
        }
        assertThat(subscriptionsOf(owner)).isZero();
    }

    @Test
    void aPaymentLinkedToAnotherInvoiceIsNotEvidence() {
        String i = invoice(m());
        String elsewhere = invoice(m());
        Ev e = new Ev(payment(elsewhere, m()), i);
        refused(() -> activate(owner, activation(e)), Code.NOT_VERIFIED);
        assertThatThrownBy(() -> insertBySql(owner, e, "MODEL_4", START, END)).isInstanceOf(Exception.class);
    }

    @Test
    void aPaymentWithNoInvoiceIsNotEvidence() {
        String i = invoice(m());
        Ev e = new Ev(payment(null, m()), i);
        refused(() -> activate(owner, activation(e)), Code.NOT_VERIFIED);
    }

    @Test
    void anotherRestaurantCantUseThisEvidence() {
        Ev e = evidence();
        refused(() -> activate(other, activation(e)), Code.OWNER_MISMATCH);
        assertThat(subscriptionsOf(other)).isZero();
        assertThat(periods()).isZero();
    }

    private String checkout(Tenant t, String provider) {
        return insert("checkout_session", m("business_id", lit(t.businessId()), "owner_user_id", lit(t.owner().getId()), "price_id", lit(price), "config_version", "1", "kind", "'NEW'", "provider", lit(provider), "status", "'EXPIRED'",
                "idempotency_key", lit(id()), "request_hash", "'h'", "expires_at", "now() + interval '1 hour'", "expected_amount_minor", "1000", "expected_currency", "'ETB'", "created_at", "now()"), m());
    }

    @Test
    void aCheckoutOfAnotherRestaurantIsRefused() {
        Ev e = evidence(m(), m("checkout_session_id", lit(checkout(other, "P1"))));
        refused(() -> activate(owner, activation(e)), Code.OWNER_MISMATCH);
    }

    @Test
    void aCheckoutOfAnotherProviderIsRefused() {
        Ev e = evidence(m(), m("checkout_session_id", lit(checkout(owner, "P2"))));
        refused(() -> activate(owner, activation(e)), Code.OWNER_MISMATCH);
    }

    @Test
    void differentCheckoutsOnPaymentAndInvoiceAreRefused() {
        Ev e = evidence(m("checkout_session_id", lit(checkout(owner, "P1"))), m("checkout_session_id", lit(checkout(owner, "P1"))));
        refused(() -> activate(owner, activation(e)), Code.OWNER_MISMATCH);
    }

    @Test
    void aMatchingCheckoutIsAccepted() {
        Ev e = evidence(m(), m("checkout_session_id", lit(checkout(owner, "P1"))));
        assertThat(activate(owner, activation(e)).getSource()).isEqualTo("PROVIDER");
    }

    @Test
    void evidenceWithNoCheckoutIsAccepted() {
        assertThat(active().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void aGrantedPlanOtherThanTheInvoicesIsRefused() {
        Ev e = evidence();
        refused(() -> activate(owner, activation(e, "MODEL_3", "P1", START, END)), Code.PLAN_MISMATCH);
    }

    @Test
    void aGrantedPeriodOtherThanTheInvoicesIsRefused() {
        Ev e = evidence();
        refused(() -> activate(owner, activation(e, "MODEL_4", "P1", START, NEXT)), Code.PERIOD_MISMATCH);
    }

    @Test
    void aClaimedProviderOtherThanThePaymentsIsRefused() {
        Ev e = evidence();
        refused(() -> activate(owner, activation(e, "MODEL_4", "P2", START, END)), Code.OWNER_MISMATCH);
    }

    @Test
    void aRestaurantWithoutABillingAccountIsRefused() {
        Tenant t = data.tenant("no-account");
        Ev e = evidence(m("business_id", lit(t.businessId())), m("business_id", lit(t.businessId())));
        refused(() -> activate(t, activation(e)), Code.PLAN_MISMATCH);
    }

    @Test
    void anotherMarketsPriceIsRefused() {
        Tenant us = data.tenant("us-owner");
        jdbc.update("update business set country = 'US' where id = ?", us.businessId());
        account(us, "US", "P1");
        Ev e = evidence(m("business_id", lit(us.businessId())), m("business_id", lit(us.businessId())));
        refused(() -> activate(us, activation(e)), Code.PLAN_MISMATCH);
    }

    // ---- one-time consumption and replay (MT-02)

    @Test
    void repeatingAnActivationIsANoOp() {
        Ev e = evidence();
        BusinessSubscription first = activate(owner, activation(e));
        BusinessSubscription second = activate(owner, activation(e));
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(periods()).isEqualTo(1);
        assertThat(subscriptionsOf(owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = 'SUBSCRIPTION_PAID_ACTIVATED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void repeatingARenewalIsANoOp() {
        BusinessSubscription s = active();
        Ev e = renewalEvidence();
        renew(s, e, END, NEXT);
        int version = jdbc.queryForObject("select row_version from business_subscription where id = ?", Integer.class, s.getId());
        renew(s, e, END, NEXT);
        assertThat(periods()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select row_version from business_subscription where id = ?", Integer.class, s.getId())).isEqualTo(version);
    }

    @Test
    void consumedEvidenceCantBeUsedForADifferentPeriod() {
        Ev e = evidence();
        BusinessSubscription s = activate(owner, activation(e));
        refused(() -> renew(s, new Ev(e.payment(), e.invoice()), END, NEXT), Code.PERIOD_MISMATCH);
        assertThat(periods()).isEqualTo(1);
    }

    @Test
    void consumedEvidenceCantActivateAgainAfterTheSubscriptionEnded() {
        Ev e = evidence();
        BusinessSubscription s = activate(owner, activation(e));
        jdbc.update("update business_subscription set status = 'EXPIRED', ended_at = now() where id = ?", s.getId());
        refused(() -> activate(owner, activation(e)), Code.ALREADY_CONSUMED);
        assertThat(periods()).isEqualTo(1);
        assertThat(subscriptionsOf(owner)).isEqualTo(1);
    }

    @Test
    void aSecondPaymentForAConsumedInvoiceIsRefused() {
        Ev e = evidence();
        activate(owner, activation(e));
        String second = payment(e.invoice(), m());
        jdbc.update("update business_subscription set status = 'EXPIRED', ended_at = now()");
        refused(() -> activate(owner, activation(new Ev(second, e.invoice()))), Code.ALREADY_CONSUMED);
        assertThat(periods()).isEqualTo(1);
    }

    private List<Object> race(int threads, Callable<Object> work) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Object>> all = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                all.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return work.call();
                }));
            }
            List<Object> out = new ArrayList<>();
            for (Future<Object> f : all) out.add(f.get(60, TimeUnit.SECONDS));
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoThreadsApplyingOnePaymentMakeOnePeriod() throws Exception {
        Ev e = evidence();
        race(6, () -> activate(owner, activation(e)).getId());
        assertThat(periods()).isEqualTo(1);
        assertThat(subscriptionsOf(owner)).isEqualTo(1);
    }

    @Test
    void twoThreadsRenewingWithOnePaymentMakeOnePeriod() throws Exception {
        BusinessSubscription s = active();
        Ev e = renewalEvidence();
        race(6, () -> renew(s, e, END, NEXT).getId());
        assertThat(periods()).isEqualTo(2);
    }

    // ---- renew and markPastDue

    @Test
    void aRenewalMovesThePeriodForward() {
        BusinessSubscription s = active();
        renew(s, renewalEvidence(), END, NEXT);
        Map<String, Object> row = jdbc.queryForMap("select status, period_end = '2030-03-01T00:00:00Z'::timestamptz as forward, current_period_start = '2030-02-01T00:00:00Z'::timestamptz as started from business_subscription where id = ?", s.getId());
        assertThat(row).containsEntry("status", "ACTIVE").containsEntry("forward", true).containsEntry("started", true);
    }

    @Test
    void aRenewalRestoresAPastDueRow() {
        BusinessSubscription s = active();
        locked(owner, () -> lifecycle.markPastDue(s.getId(), END, Instant.now()));
        renew(s, renewalEvidence(), END, NEXT);
        assertThat(jdbc.queryForMap("select status, past_due_since from business_subscription where id = ?", s.getId())).containsEntry("status", "ACTIVE").containsEntry("past_due_since", null);
    }

    @Test
    void aRenewalMustStartWhereTheCurrentPeriodEnds() {
        BusinessSubscription s = active();
        Ev gap = evidence(m(), m("period_start", ts(NEXT), "period_end", ts(Instant.parse("2030-04-01T00:00:00Z"))));
        refused(() -> renew(s, gap, NEXT, Instant.parse("2030-04-01T00:00:00Z")), Code.PERIOD_MISMATCH);
        assertThat(periods()).isEqualTo(1);
    }

    @Test
    void aRenewalIsRefusedForANonProviderRow() {
        String id = manual();
        Ev e = renewalEvidence();
        assertThatThrownBy(() -> locked(owner, () -> lifecycle.renew(id, new PeriodExtension(END, NEXT, TestEvidence.ref(e.payment(), e.invoice()), Instant.now())))).isInstanceOf(Exception.class);
        assertThat(periods()).isZero();
    }

    @Test
    void anEndedRowCantBeRenewed() {
        BusinessSubscription s = active();
        jdbc.update("update business_subscription set status = 'EXPIRED', ended_at = now() where id = ?", s.getId());
        Ev e = renewalEvidence();
        assertThatThrownBy(() -> renew(s, e, END, NEXT)).isInstanceOf(IllegalTransitionException.class);
        assertThat(periods()).isEqualTo(1);
    }

    @Test
    void anotherSubscriptionsRenewalEvidenceIsRefused() {
        BusinessSubscription s = active();
        Ev e = evidence(m("business_id", lit(other.businessId())), m("business_id", lit(other.businessId()), "period_start", ts(END), "period_end", ts(NEXT)));
        refused(() -> renew(s, e, END, NEXT), Code.OWNER_MISMATCH);
    }

    @Test
    void markPastDueRecordsTheFailureOnce() {
        BusinessSubscription s = active();
        locked(owner, () -> lifecycle.markPastDue(s.getId(), END, Instant.now()));
        int version = jdbc.queryForObject("select row_version from business_subscription where id = ?", Integer.class, s.getId());
        locked(owner, () -> lifecycle.markPastDue(s.getId(), END, Instant.now()));
        assertThat(jdbc.queryForMap("select status, row_version from business_subscription where id = ?", s.getId())).containsEntry("status", "PAST_DUE").containsEntry("row_version", version);
    }

    @Test
    void markPastDueIsRefusedForANonProviderRow() {
        String id = manual();
        assertThatThrownBy(() -> locked(owner, () -> lifecycle.markPastDue(id, END, Instant.now()))).isInstanceOf(IllegalTransitionException.class);
    }

    // ---- replacement

    private BusinessSubscription replace(String expected, Ev e, Instant start, Instant end, TransitionReason why) {
        return locked(owner, () -> lifecycle.replacePaid(owner.businessId(), expected, activation(e, "MODEL_4", "P1", start, end), why));
    }

    @Test
    void aPaidReplacementEndsTheOldRowAndStartsTheNew() {
        String old = manual();
        BusinessSubscription next = replace(old, evidence(), START, END, TransitionReason.CHANGED);
        assertThat(jdbc.queryForMap("select status, ended_reason, replaced_by_id from business_subscription where id = ?", old)).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "CHANGED").containsEntry("replaced_by_id", next.getId());
        assertThat(next.getSource()).isEqualTo("PROVIDER");
        assertThat(periods()).isEqualTo(1);
    }

    @Test
    void aTrialConvertsToAPaidRow() {
        String trial = locked(owner, () -> lifecycle.startTrial(owner.businessId(), 1, Instant.now(), END, new Actor(admin.getId()))).getId();
        replace(trial, evidence(), START, END, TransitionReason.CONVERTED);
        assertThat(jdbc.queryForMap("select status, ended_reason from business_subscription where id = ?", trial)).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "CONVERTED");
    }

    @Test
    void aReplacementOfAnUnexpectedRowLeavesItUntouched() {
        String old = manual();
        Map<String, Object> before = jdbc.queryForMap("select * from business_subscription where id = ?", old);
        Ev e = evidence();
        assertThatThrownBy(() -> replace("someone-else", e, START, END, TransitionReason.CHANGED)).isInstanceOf(ExpectedSubscriptionChangedException.class);
        assertThat(jdbc.queryForMap("select * from business_subscription where id = ?", old)).isEqualTo(before);
        assertThat(periods()).isZero();
    }

    @Test
    void aReplacementWithBadEvidenceLeavesTheCurrentRowUntouched() {
        String old = manual();
        Map<String, Object> before = jdbc.queryForMap("select * from business_subscription where id = ?", old);
        Ev bad = evidence(m("kind", "'REFUND'"), m()); // AGF V23.4: a PENDING charge can no longer exist; this one can, and is still not evidence
        refused(() -> replace(old, bad, START, END, TransitionReason.CHANGED), Code.NOT_VERIFIED);
        assertThat(jdbc.queryForMap("select * from business_subscription where id = ?", old)).isEqualTo(before);
        assertThat(subscriptionsOf(owner)).isEqualTo(1);
    }

    @Test
    void aReplacementReasonThatIsntOneIsRefused() {
        String old = manual();
        Ev e = evidence();
        assertThatThrownBy(() -> replace(old, e, START, END, TransitionReason.ADMIN)).isInstanceOf(IllegalTransitionException.class);
        assertThat(jdbc.queryForObject("select status from business_subscription where id = ?", String.class, old)).isEqualTo("ACTIVE");
    }

    @Test
    void repeatingAReplacementIsANoOp() {
        String old = manual();
        Ev e = evidence();
        BusinessSubscription first = replace(old, e, START, END, TransitionReason.CHANGED);
        BusinessSubscription again = replace(old, e, START, END, TransitionReason.CHANGED);
        assertThat(again.getId()).isEqualTo(first.getId());
        assertThat(subscriptionsOf(owner)).isEqualTo(2);
        assertThat(periods()).isEqualTo(1);
    }

    // ---- without the application, the database still refuses

    @Test
    void aConsumedPaymentIsRefusedBySqlToo() {
        Ev e = evidence();
        activate(owner, activation(e));
        jdbc.update("update business_subscription set status = 'EXPIRED', ended_at = now()");
        assertThatThrownBy(() -> insertBySql(owner, e, "MODEL_4", START, END)).isInstanceOf(Exception.class);
        assertThat(periods()).isEqualTo(1);
    }
}
