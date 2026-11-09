package com.agafari.com.migration;

import com.agafari.com.jpa.entity.User;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AGF SUB-R4-02: V23.2, by SQL only (F1, F2, MT-02 at the database level). Amounts are fixtures, not prices (AD-10 is open). */
class LedgerEvidenceMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "v232_dev";
    private static final String START = "'2030-01-01T00:00:00Z'::timestamptz";
    private static final String END = "'2030-02-01T00:00:00Z'::timestamptz";
    private static final String NEXT_END = "'2030-03-01T00:00:00Z'::timestamptz";
    private static final String EARLIER = "'2030-01-15T00:00:00Z'::timestamptz";
    private static final String VERIFIED = "SIGNED_WEBHOOK+API_FETCH";

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    private User admin;
    private Tenant owner;
    private Tenant other;
    private String price;
    private String otherPlanPrice;
    private String checkout;
    private String otherRestaurantCheckout;
    private String otherProviderCheckout;

    private static String lit(String v) {
        return "'" + v + "'";
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static Map<String, String> m(String... kv) {
        Map<String, String> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) r.put(kv[i], kv[i + 1]);
        return r;
    }

    @BeforeEach
    void setUp() {
        dropSchema();
        admin = data.admin("ledger-admin");
        owner = data.tenant("ledger-owner");
        other = data.tenant("ledger-other");
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        price = id();
        otherPlanPrice = id();
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, valid_from) values (?, 'MODEL_4', 'ET', 'ETB', 'MONTH', 1000, 1, now())", price);
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, valid_from) values (?, 'MODEL_3', 'ET', 'ETB', 'MONTH', 1000, 1, now())", otherPlanPrice);
        checkout = checkoutFor(owner, "P1");
        otherRestaurantCheckout = checkoutFor(other, "P1");
        otherProviderCheckout = checkoutFor(owner, "P2");
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
    }

    private String checkoutFor(Tenant t, String provider) {
        String c = id();
        jdbc.update("insert into checkout_session (id, business_id, owner_user_id, price_id, config_version, kind, provider, status, idempotency_key, request_hash, expires_at, expected_amount_minor, expected_currency, created_at) "
                + "values (?, ?, ?, ?, 1, 'NEW', ?, 'EXPIRED', ?, 'h', now() + interval '1 hour', 1000, 'ETB', now())", c, t.businessId(), t.owner().getId(), price, provider, id());
        return c;
    }

    // ---- fixtures

    private static String insert(JdbcTemplate t, String table, Map<String, String> defaults, Map<String, String> overrides) {
        Map<String, String> row = new LinkedHashMap<>(m("id", lit(id())));
        row.putAll(defaults);
        row.putAll(overrides);
        t.update("insert into " + table + " (" + String.join(", ", row.keySet()) + ") values (" + String.join(", ", row.values()) + ")");
        return row.get("id").replace("'", "");
    }

    private String invoice(JdbcTemplate t, Map<String, String> o) {
        return insert(t, "billing_invoice", m("business_id", lit(owner.businessId()), "checkout_session_id", lit(checkout), "price_id", lit(price),
                "period_start", START, "period_end", END, "amount_minor", "1000", "currency", "'ETB'", "status", "'PAID'", "provider_invoice_ref", lit(id())), o);
    }

    private String payment(JdbcTemplate t, String invoice, Map<String, String> o) {
        return insert(t, "payment", m("business_id", lit(owner.businessId()), "invoice_id", lit(invoice), "checkout_session_id", lit(checkout), "provider", "'P1'",
                "provider_payment_ref", lit(id()), "amount_minor", "1000", "currency", "'ETB'", "kind", "'CHARGE'", "status", "'SUCCEEDED'",
                "occurred_at", "now()", "verified_at", "now()", "verification", lit(VERIFIED)), o);
    }

    private String subscription(JdbcTemplate t, Map<String, String> o) {
        return insert(t, "business_subscription", m("business_id", lit(owner.businessId()), "plan_code", "'MODEL_4'", "status", "'ACTIVE'", "config_version", "1",
                "period_end", END, "source", "'PROVIDER'", "provider", "'P1'", "price_id", lit(price), "created_at", "now()", "created_by_user_id", lit(admin.getId()),
                "updated_at", "now()", "updated_by_user_id", lit(admin.getId()), "row_version", "0"), o);
    }

    private String period(JdbcTemplate t, String sub, String invoice, String payment, Map<String, String> o) {
        return insert(t, "subscription_period", m("subscription_id", lit(sub), "business_id", lit(owner.businessId()), "period_start", START, "period_end", END,
                "invoice_id", lit(invoice), "payment_id", lit(payment)), o);
    }

    /** One transaction, committed: deferred triggers fire at the end of it. */
    private void tx(Consumer<JdbcTemplate> work) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                work.accept(new JdbcTemplate(new SingleConnectionDataSource(c, true)));
                c.commit();
            } finally {
                if (!c.isClosed()) {
                    c.rollback();
                    c.setAutoCommit(true);
                }
            }
        }
    }

    private String[] activated() throws SQLException {
        String[] ids = new String[3];
        tx(t -> {
            ids[1] = invoice(t, m());
            ids[2] = payment(t, ids[1], m());
            ids[0] = subscription(t, m());
            period(t, ids[0], ids[1], ids[2], m());
        });
        return ids;
    }

    private void renew(String sub, String newEnd, boolean withEvidence) throws SQLException {
        tx(t -> {
            if (withEvidence) {
                String i = invoice(t, m("period_start", END, "period_end", newEnd));
                String p = payment(t, i, m());
                period(t, sub, i, p, m("period_start", END, "period_end", newEnd));
            }
            t.update("update business_subscription set period_end = " + newEnd + " where id = ?", sub);
        });
    }

    private void withGuardOff(Runnable work) {
        jdbc.execute("alter table subscription_period disable trigger subscription_period_guard");
        try {
            work.run();
        } finally {
            jdbc.execute("alter table subscription_period enable trigger subscription_period_guard");
        }
    }

    // ---- AC1: a PROVIDER row needs a period at commit

    @Test
    void aProviderRowWithoutAPeriodFailsAtCommit() {
        assertThatThrownBy(() -> tx(t -> subscription(t, m()))).hasMessageContaining("PROVIDER subscription without a verified paid period");
        assertThat(jdbc.queryForObject("select count(*) from business_subscription where source = 'PROVIDER'", Integer.class)).isZero();
    }

    @Test
    void theCheckIsDeferredToCommitNotTheStatement() {
        assertThatCode(() -> tx(t -> {
            String s = subscription(t, m());
            assertThat(t.queryForObject("select count(*) from business_subscription where id = ?", Integer.class, s)).isEqualTo(1);
            String i = invoice(t, m());
            period(t, s, i, payment(t, i, m()), m());
        })).doesNotThrowAnyException();
    }

    @Test
    void activationInTheDesignedInsertOrderIsAccepted() {
        assertThatCode(this::activated).doesNotThrowAnyException();
    }

    @Test
    void aPeriodMustMatchTheRowsEnd() {
        assertThatThrownBy(() -> tx(t -> {
            String s = subscription(t, m("period_end", NEXT_END));
            String i = invoice(t, m());
            period(t, s, i, payment(t, i, m()), m());
        })).hasMessageContaining("PROVIDER subscription without a verified paid period");
    }

    @Test
    void anotherSubscriptionsPeriodDoesNotCount() throws Exception {
        String[] first = activated();
        jdbc.update("update business_subscription set status = 'EXPIRED', ended_at = now(), period_end = period_end where id = ?", first[0]);
        assertThatThrownBy(() -> tx(t -> subscription(t, m()))).hasMessageContaining("PROVIDER subscription without a verified paid period");
    }

    @Test
    void aNullPeriodEndAtInsertFailsAtCommit() {
        assertThatThrownBy(() -> tx(t -> subscription(t, m("period_end", "null")))).hasMessageContaining("PROVIDER subscription without a verified paid period");
    }

    @Test
    void aLaterUpdateInTheSameTransactionIsWhatIsChecked() {
        assertThatCode(() -> tx(t -> {
            String s = subscription(t, m("period_end", EARLIER));
            t.update("update business_subscription set period_end = " + END + " where id = ?", s);
            String i = invoice(t, m());
            period(t, s, i, payment(t, i, m()), m());
        })).doesNotThrowAnyException();
    }

    // ---- AC2: every mismatch

    static Stream<Arguments> forgedEvidence() {
        String notVerified = "payment is not verified evidence";
        String restaurant = "belong to one restaurant";
        String amount = "amount and currency";
        String period = "period doesn't match";
        return Stream.of(
                Arguments.of("pending", m("status", "'PENDING'"), m(), m(), notVerified),
                Arguments.of("failed", m("status", "'FAILED'"), m(), m(), notVerified),
                Arguments.of("SUCCEEDED but never verified", m("verified_at", "null", "verification", "null"), m(), m(), notVerified),
                Arguments.of("no verified_at", m("verified_at", "null"), m(), m(), notVerified),
                Arguments.of("no verification", m("verification", "null"), m(), m(), notVerified),
                Arguments.of("unknown verification", m("verification", "'TRUST_ME'"), m(), m(), notVerified),
                Arguments.of("refund", m("kind", "'REFUND'"), m(), m(), notVerified),
                Arguments.of("chargeback", m("kind", "'CHARGEBACK'"), m(), m(), notVerified),
                Arguments.of("open invoice", m(), m("status", "'OPEN'"), m(), "invoice is not paid"),
                Arguments.of("void invoice", m(), m("status", "'VOID'"), m(), "invoice is not paid"),
                Arguments.of("uncollectible invoice", m(), m("status", "'UNCOLLECTIBLE'"), m(), "invoice is not paid"),
                Arguments.of("refunded invoice", m(), m("status", "'REFUNDED'"), m(), "invoice is not paid"),
                Arguments.of("payment of another restaurant", m("business_id", "$OTHER"), m(), m(), restaurant),
                Arguments.of("invoice of another restaurant", m(), m("business_id", "$OTHER"), m(), restaurant),
                Arguments.of("period row of another restaurant", m(), m(), m("business_id", "$OTHER"), restaurant),
                Arguments.of("checkout of another restaurant on the invoice", m("checkout_session_id", "null"), m("checkout_session_id", "$OTHER_CHECKOUT"), m(), restaurant),
                Arguments.of("checkout of another restaurant on the payment", m("checkout_session_id", "$OTHER_CHECKOUT"), m("checkout_session_id", "null"), m(), restaurant),
                Arguments.of("different checkouts", m("checkout_session_id", "$OTHER_PROVIDER_CHECKOUT"), m(), m(), "payment and invoice belong to different checkouts"),
                Arguments.of("amount", m("amount_minor", "999"), m(), m(), amount),
                Arguments.of("currency", m("currency", "'USD'"), m(), m(), amount),
                Arguments.of("period start", m(), m(), m("period_start", EARLIER), period),
                Arguments.of("period end", m(), m(), m("period_end", NEXT_END), period),
                Arguments.of("plan", m(), m("price_id", "$OTHER_PLAN_PRICE"), m(), "plan doesn't match"),
                Arguments.of("provider", m("provider", "'P2'"), m(), m(), "provider doesn't match"),
                Arguments.of("payment from another provider with no checkout", m("provider", "'P2'", "checkout_session_id", "null"), m("checkout_session_id", "null"), m(), "provider doesn't match"),
                Arguments.of("checkout of another provider", m("checkout_session_id", "null"), m("checkout_session_id", "$OTHER_PROVIDER_CHECKOUT"), m(), "provider doesn't match"));
    }

    private Map<String, String> bind(Map<String, String> o) {
        Map<String, String> r = new LinkedHashMap<>();
        o.forEach((k, v) -> r.put(k, switch (v) {
            case "$OTHER" -> lit(other.businessId());
            case "$OTHER_CHECKOUT" -> lit(otherRestaurantCheckout);
            case "$OTHER_PROVIDER_CHECKOUT" -> lit(otherProviderCheckout);
            case "$OTHER_PLAN_PRICE" -> lit(otherPlanPrice);
            default -> v;
        }));
        return r;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("forgedEvidence")
    void forgedEvidenceIsRefused(String name, Map<String, String> onPayment, Map<String, String> onInvoice, Map<String, String> onPeriod, String message) {
        assertThatThrownBy(() -> tx(t -> {
            String s = subscription(t, m());
            String i = invoice(t, bind(onInvoice));
            period(t, s, i, payment(t, i, bind(onPayment)), bind(onPeriod));
        })).hasMessageContaining(message);
    }

    @Test
    void aSubscriptionOfAnotherRestaurantIsRefused() {
        assertThatThrownBy(() -> tx(t -> {
            String s = subscription(t, m("business_id", lit(other.businessId())));
            String i = invoice(t, m());
            period(t, s, i, payment(t, i, m()), m());
        })).hasMessageContaining("belong to one restaurant");
    }

    @Test
    void aPaymentForAnotherInvoiceIsRefused() {
        assertThatThrownBy(() -> tx(t -> {
            String s = subscription(t, m());
            String i = invoice(t, m());
            String elsewhere = invoice(t, m());
            period(t, s, i, payment(t, elsewhere, m()), m());
        })).hasMessageContaining("payment doesn't belong to this invoice");
    }

    @Test
    void aPaymentWithNoInvoiceIsRefused() {
        assertThatThrownBy(() -> tx(t -> {
            String s = subscription(t, m());
            String i = invoice(t, m());
            period(t, s, i, payment(t, null, m("invoice_id", "null")), m());
        })).hasMessageContaining("payment doesn't belong to this invoice");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANUAL", "OWNER"})
    void aPeriodOnlyAttachesToAProviderRow(String source) {
        assertThatThrownBy(() -> tx(t -> {
            String s = subscription(t, m("source", lit(source), "provider", "null", "price_id", "null"));
            String i = invoice(t, m());
            period(t, s, i, payment(t, i, m()), m());
        })).hasMessageContaining("periods belong to PROVIDER subscriptions");
    }

    @Test
    void missingEvidenceIsRefusedByForeignKeys() {
        assertThatThrownBy(() -> tx(t -> {
            String s = subscription(t, m());
            String i = invoice(t, m());
            period(t, s, i, "no-such", m());
        })).hasMessageContaining("fk_subscription_period_payment");
        assertThatThrownBy(() -> tx(t -> {
            String s = subscription(t, m());
            String i = invoice(t, m());
            period(t, s, "no-such", payment(t, i, m()), m());
        })).hasMessageContaining("fk_subscription_period_invoice");
        assertThatThrownBy(() -> tx(t -> {
            String i = invoice(t, m());
            period(t, "no-such", i, payment(t, i, m()), m());
        })).hasMessageContaining("fk_subscription_period_subscription");
    }

    @Test
    void anInvoiceThatStoppedBeingPaidIsStaleEvidence() {
        assertThatThrownBy(() -> tx(t -> {
            String s = subscription(t, m());
            String i = invoice(t, m());
            String p = payment(t, i, m());
            t.update("update billing_invoice set status = 'VOID' where id = ?", i);
            period(t, s, i, p, m());
        })).hasMessageContaining("invoice is not paid");
    }

    @Test
    void anInvoiceCantBeVoidedWhileItIsBeingConsumed() throws Exception {
        try (Connection a = dataSource.getConnection(); Connection b = dataSource.getConnection()) {
            a.setAutoCommit(false);
            JdbcTemplate ta = new JdbcTemplate(new SingleConnectionDataSource(a, true));
            String i = invoice(ta, m());
            String p = payment(ta, i, m());
            a.commit();
            String s = subscription(ta, m());
            period(ta, s, i, p, m());
            JdbcTemplate tb = new JdbcTemplate(new SingleConnectionDataSource(b, true));
            tb.execute("set lock_timeout = '300ms'");
            assertThatThrownBy(() -> tb.update("update billing_invoice set status = 'VOID' where id = ?", i)).hasMessageContaining("lock timeout");
            tb.execute("reset lock_timeout");
            a.rollback();
            a.setAutoCommit(true);
        }
    }

    // ---- AC3 and NC-13: one-time consumption

    @Test
    void aSecondPeriodWithTheSamePaymentAndInvoiceFails() throws Exception {
        String[] first = activated();
        assertThatThrownBy(() -> tx(t -> period(t, first[0], first[1], first[2], m()))).hasMessageContaining("uk_subscription_period_");
    }

    @Test
    void aPaymentIsConsumedOnce() throws Exception {
        String[] first = activated();
        String secondInvoice = invoice(jdbc, m());
        assertThatThrownBy(() -> withGuardOff(() -> period(jdbc, first[0], secondInvoice, first[2], m("period_start", END, "period_end", NEXT_END)))).hasMessageContaining("uk_subscription_period_payment");
    }

    @Test
    void anInvoiceIsConsumedOnce() throws Exception {
        String[] first = activated();
        String secondPayment = payment(jdbc, first[1], m());
        assertThatThrownBy(() -> withGuardOff(() -> period(jdbc, first[0], first[1], secondPayment, m("period_start", END, "period_end", NEXT_END)))).hasMessageContaining("uk_subscription_period_invoice");
    }

    @Test
    void aConsumedPaymentCantPayAnotherSubscription() throws Exception {
        String[] first = activated();
        assertThatThrownBy(() -> tx(t -> {
            String s = subscription(t, m("business_id", lit(other.businessId())));
            period(t, s, first[1], first[2], m("business_id", lit(other.businessId())));
        })).hasMessageContaining("belong to one restaurant");
    }

    @Test
    void aSubscriptionPeriodStartIsUsedOnce() throws Exception {
        String[] first = activated();
        assertThatThrownBy(() -> tx(t -> {
            String i = invoice(t, m("period_end", NEXT_END));
            period(t, first[0], i, payment(t, i, m()), m("period_end", NEXT_END));
        })).hasMessageContaining("uk_subscription_period_start");
    }

    @Test
    void aPeriodMustEndAfterItStarts() throws Exception {
        String[] first = activated();
        String i = invoice(jdbc, m());
        String p = payment(jdbc, i, m());
        assertThatThrownBy(() -> withGuardOff(() -> period(jdbc, first[0], i, p, m("period_start", NEXT_END, "period_end", NEXT_END)))).hasMessageContaining("ck_subscription_period_dates");
    }

    // ---- AC4 and NC-15: PROVIDER period_end

    @Test
    void aRenewalWithEvidenceMovesThePeriodForward() throws Exception {
        String[] a = activated();
        assertThatCode(() -> renew(a[0], NEXT_END, true)).doesNotThrowAnyException();
        assertThat(jdbc.queryForObject("select count(*) from subscription_period where subscription_id = ?", Integer.class, a[0])).isEqualTo(2);
    }

    @Test
    void aForwardChangeWithoutEvidenceFailsAtCommit() throws Exception {
        String[] a = activated();
        assertThatThrownBy(() -> renew(a[0], NEXT_END, false)).hasMessageContaining("PROVIDER subscription without a verified paid period");
        assertThat(jdbc.queryForObject("select period_end = " + END + " from business_subscription where id = ?", Boolean.class, a[0])).isTrue();
    }

    @Test
    void aBackwardChangeFailsEvenWithEvidence() throws Exception {
        String[] a = activated();
        assertThatThrownBy(() -> tx(t -> {
            String dec = "'2029-12-01T00:00:00Z'::timestamptz";
            String i = invoice(t, m("period_start", dec, "period_end", EARLIER));
            period(t, a[0], i, payment(t, i, m()), m("period_start", dec, "period_end", EARLIER));
            t.update("update business_subscription set period_end = " + EARLIER + " where id = ?", a[0]);
        })).hasMessageContaining("a PROVIDER period can only move forward");
        assertThatThrownBy(() -> jdbc.update("update business_subscription set period_end = " + EARLIER + " where id = ?", a[0])).hasMessageContaining("a PROVIDER period can only move forward");
    }

    @Test
    void aNullPeriodEndIsRefused() throws Exception {
        String[] a = activated();
        assertThatThrownBy(() -> jdbc.update("update business_subscription set period_end = null where id = ?", a[0])).hasMessageContaining("a PROVIDER period can only move forward");
    }

    @Test
    void anUnchangedPeriodEndPassesOnAStatusChange() throws Exception {
        String[] a = activated();
        assertThatCode(() -> jdbc.update("update business_subscription set status = 'PAST_DUE', past_due_since = now() where id = ?", a[0])).doesNotThrowAnyException();
        assertThatCode(() -> jdbc.update("update business_subscription set status = 'EXPIRED', past_due_since = null, ended_at = now() where id = ?", a[0])).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"price_id = null", "provider = null", "provider = 'P2'", "provider_subscription_ref = 'late'"})
    void aProviderRowsBillingColumnsAreImmutable(String change) throws Exception {
        String[] a = activated();
        assertThatThrownBy(() -> jdbc.update("update business_subscription set " + change + " where id = ?", a[0])).hasMessageContaining("can't be changed");
    }

    @Test
    void aProviderRowsPriceCantChangeToAnother() throws Exception {
        String[] a = activated();
        assertThatThrownBy(() -> jdbc.update("update business_subscription set price_id = ? where id = ?", otherPlanPrice, a[0])).hasMessageContaining("can't be changed");
    }

    @Test
    void aSourceCantBecomeOrStopBeingProvider() throws Exception {
        String[] a = activated();
        assertThatThrownBy(() -> jdbc.update("update business_subscription set source = 'MANUAL' where id = ?", a[0])).hasMessageContaining("can't be changed");
        Tenant t = data.tenant("ledger-manual");
        String manual = subscription(jdbc, m("business_id", lit(t.businessId()), "source", "'MANUAL'", "provider", "null", "price_id", "null"));
        assertThatThrownBy(() -> jdbc.update("update business_subscription set source = 'PROVIDER' where id = ?", manual)).hasMessageContaining("can't be changed");
    }

    @Test
    void v20RulesStillApply() throws Exception {
        String[] a = activated();
        assertThatThrownBy(() -> jdbc.update("delete from business_subscription where id = ?", a[0])).hasMessageContaining("can't be deleted");
        assertThatThrownBy(() -> jdbc.update("update business_subscription set plan_code = 'MODEL_3' where id = ?", a[0])).hasMessageContaining("can't be changed");
        jdbc.update("update business_subscription set status = 'EXPIRED', ended_at = now() where id = ?", a[0]);
        assertThatThrownBy(() -> jdbc.update("update business_subscription set ended_at = now() where id = ?", a[0])).hasMessageContaining("ended subscriptions can't be changed");
    }

    // ---- AC5: R-4

    @ParameterizedTest
    @ValueSource(strings = {"MANUAL", "OWNER", "TRIAL"})
    void adminPeriodEditsStillPassForOtherSources(String source) {
        String status = source.equals("TRIAL") ? "TRIAL" : "ACTIVE";
        String s = subscription(jdbc, m("source", lit(source), "status", lit(status), "plan_code", source.equals("TRIAL") ? "'MODEL_1'" : "'MODEL_4'", "provider", "null", "price_id", "null"));
        assertThatCode(() -> tx(t -> t.update("update business_subscription set period_end = " + NEXT_END + " where id = ?", s))).doesNotThrowAnyException();
        assertThatCode(() -> tx(t -> t.update("update business_subscription set period_end = " + EARLIER + " where id = ?", s))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANUAL", "OWNER"})
    void aPeriodEndCanStillBeClearedForOtherSources(String source) {
        String s = subscription(jdbc, m("source", lit(source), "provider", "null", "price_id", "null"));
        assertThatCode(() -> tx(t -> t.update("update business_subscription set period_end = null where id = ?", s))).doesNotThrowAnyException();
    }

    @Test
    void anOtherSourcesPriceMayStillBeSetOnce() {
        String s = subscription(jdbc, m("source", "'MANUAL'", "provider", "null", "price_id", "null"));
        assertThatCode(() -> jdbc.update("update business_subscription set price_id = ? where id = ?", price, s)).doesNotThrowAnyException();
    }

    // ---- append-only ledger and AD-19

    @Test
    void aPaymentCantBeChanged() {
        String p = payment(jdbc, invoice(jdbc, m()), m());
        assertThatThrownBy(() -> jdbc.update("update payment set verified_at = null where id = ?", p)).hasMessageContaining("payments can't be changed or removed");
        assertThatThrownBy(() -> jdbc.update("update payment set status = 'FAILED' where id = ?", p)).hasMessageContaining("payments can't be changed or removed");
    }

    @Test
    void ledgerRowsCantBeDeleted() throws Exception {
        String[] a = activated();
        assertThatThrownBy(() -> jdbc.update("delete from payment where id = ?", a[2])).hasMessageContaining("payments can't be changed or removed");
        assertThatThrownBy(() -> jdbc.update("delete from billing_invoice where id = ?", a[1])).hasMessageContaining("invoices can't be removed");
        assertThatThrownBy(() -> jdbc.update("delete from subscription_period where subscription_id = ?", a[0])).hasMessageContaining("subscription periods can't be changed or removed");
    }

    @Test
    void noForeignKeyCanCascadeIntoTheLedger() {
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where contype = 'f' and confdeltype <> 'a' and conrelid::regclass::text in ('payment', 'billing_invoice', 'subscription_period')", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where contype = 'f' and conrelid::regclass::text in ('payment', 'billing_invoice', 'subscription_period')", Integer.class)).isEqualTo(10);
    }

    @Test
    void aSubscriptionPeriodCantBeChanged() throws Exception {
        String[] a = activated();
        assertThatThrownBy(() -> jdbc.update("update subscription_period set period_end = " + NEXT_END + " where subscription_id = ?", a[0])).hasMessageContaining("subscription periods can't be changed or removed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPEN", "PAID", "VOID", "UNCOLLECTIBLE", "REFUNDED"})
    void anInvoicesStatusMayChange(String status) {
        String i = invoice(jdbc, m("status", "'OPEN'"));
        assertThatCode(() -> jdbc.update("update billing_invoice set status = ? where id = ?", status, i)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id = 'x'", "business_id = '$OTHER'", "checkout_session_id = null", "price_id = '$PLAN'", "period_start = now()", "period_end = now() + interval '1 year'", "amount_minor = 1", "currency = 'USD'", "provider_invoice_ref = 'changed'"})
    void everythingElseOnAnInvoiceIsImmutable(String change) {
        String i = invoice(jdbc, m());
        String set = change.replace("$OTHER", other.businessId()).replace("$PLAN", otherPlanPrice);
        assertThatThrownBy(() -> jdbc.update("update billing_invoice set " + set + " where id = ?", i)).hasMessageContaining("only an invoice's status can change");
    }

    @Test
    void aStatusChangeCantCarryAnotherChange() {
        String i = invoice(jdbc, m("status", "'OPEN'"));
        assertThatThrownBy(() -> jdbc.update("update billing_invoice set status = 'PAID', amount_minor = 1 where id = ?", i)).hasMessageContaining("only an invoice's status can change");
    }

    // ---- uniqueness and checks

    @Test
    void aPaymentIsUniquePerProviderReferenceAndKind() {
        payment(jdbc, null, m("invoice_id", "null", "provider_payment_ref", "'r'"));
        assertThatThrownBy(() -> payment(jdbc, null, m("invoice_id", "null", "provider_payment_ref", "'r'"))).hasMessageContaining("uk_payment_provider_ref_kind");
        assertThatCode(() -> payment(jdbc, null, m("invoice_id", "null", "provider_payment_ref", "'r'", "kind", "'REFUND'"))).doesNotThrowAnyException();
        assertThatCode(() -> payment(jdbc, null, m("invoice_id", "null", "provider_payment_ref", "'r'", "provider", "'P2'"))).doesNotThrowAnyException();
    }

    @Test
    void anInvoiceReferenceIsUnique() {
        invoice(jdbc, m("provider_invoice_ref", "'r'"));
        assertThatThrownBy(() -> invoice(jdbc, m("provider_invoice_ref", "'r'"))).hasMessageContaining("uk_billing_invoice_provider_ref");
        invoice(jdbc, m("provider_invoice_ref", "null"));
        assertThatCode(() -> invoice(jdbc, m("provider_invoice_ref", "null"))).doesNotThrowAnyException();
    }

    @Test
    void aRenewalInvoiceMayHaveNoCheckout() {
        assertThatCode(() -> invoice(jdbc, m("checkout_session_id", "null"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SUCCEEDED", "FAILED"})
    void everyApprovedPaymentStatusIsAllowed(String status) {
        assertThatCode(() -> payment(jdbc, null, m("invoice_id", "null", "status", lit(status)))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHARGE", "REFUND", "CHARGEBACK"})
    void everyPaymentKindIsAllowed(String kind) {
        assertThatCode(() -> payment(jdbc, null, m("invoice_id", "null", "kind", lit(kind)))).doesNotThrowAnyException();
    }

    @Test
    void paymentChecksRefuseBadValues() {
        Map<String, String> none = m("invoice_id", "null");
        assertThatThrownBy(() -> payment(jdbc, null, with(none, "status", "'REFUNDED'"))).hasMessageContaining("ck_payment_status");
        assertThatThrownBy(() -> payment(jdbc, null, with(none, "kind", "'OTHER'"))).hasMessageContaining("ck_payment_kind");
        assertThatThrownBy(() -> payment(jdbc, null, with(none, "amount_minor", "0"))).hasMessageContaining("ck_payment_amount");
        assertThatThrownBy(() -> payment(jdbc, null, with(none, "currency", "'EUR'"))).hasMessageContaining("ck_payment_currency");
    }

    @Test
    void invoiceChecksRefuseBadValues() {
        assertThatThrownBy(() -> invoice(jdbc, m("status", "'NONSENSE'"))).hasMessageContaining("ck_billing_invoice_status");
        assertThatThrownBy(() -> invoice(jdbc, m("amount_minor", "0"))).hasMessageContaining("ck_billing_invoice_amount");
        assertThatThrownBy(() -> invoice(jdbc, m("currency", "'EUR'"))).hasMessageContaining("ck_billing_invoice_currency");
        assertThatThrownBy(() -> invoice(jdbc, m("period_end", START))).hasMessageContaining("ck_billing_invoice_dates");
    }

    private static Map<String, String> with(Map<String, String> base, String k, String v) {
        Map<String, String> r = new LinkedHashMap<>(base);
        r.put(k, v);
        return r;
    }

    @Test
    void ledgerForeignKeysAreEnforced() {
        assertThatThrownBy(() -> invoice(jdbc, m("business_id", "'no'"))).hasMessageContaining("fk_billing_invoice_business");
        assertThatThrownBy(() -> invoice(jdbc, m("checkout_session_id", "'no'"))).hasMessageContaining("fk_billing_invoice_checkout");
        assertThatThrownBy(() -> invoice(jdbc, m("price_id", "'no'"))).hasMessageContaining("fk_billing_invoice_price");
        assertThatThrownBy(() -> payment(jdbc, "x", m("invoice_id", "'no'"))).hasMessageContaining("fk_payment_invoice");
        assertThatThrownBy(() -> payment(jdbc, null, m("invoice_id", "null", "business_id", "'no'"))).hasMessageContaining("fk_payment_business");
        assertThatThrownBy(() -> payment(jdbc, null, m("invoice_id", "null", "checkout_session_id", "'no'"))).hasMessageContaining("fk_payment_checkout");
    }

    @Test
    void aPeriodsRestaurantMustExist() throws Exception {
        String[] a = activated();
        String i = invoice(jdbc, m());
        String p = payment(jdbc, i, m());
        assertThatThrownBy(() -> withGuardOff(() -> period(jdbc, a[0], i, p, m("period_start", END, "period_end", NEXT_END, "business_id", "'no'")))).hasMessageContaining("fk_subscription_period_business");
    }

    @ParameterizedTest
    @ValueSource(strings = {"business_id", "price_id", "period_start", "period_end", "amount_minor", "currency", "status"})
    void invoiceColumnsAreRequired(String column) {
        assertThatThrownBy(() -> invoice(jdbc, m(column, "null"))).hasMessageContaining(column);
    }

    @ParameterizedTest
    @ValueSource(strings = {"business_id", "provider", "provider_payment_ref", "amount_minor", "currency", "kind", "status", "occurred_at"})
    void paymentColumnsAreRequired(String column) {
        assertThatThrownBy(() -> payment(jdbc, null, m("invoice_id", "null", column, "null"))).hasMessageContaining(column);
    }

    @ParameterizedTest
    @ValueSource(strings = {"subscription_id", "business_id", "period_start", "period_end", "invoice_id", "payment_id"})
    void periodColumnsAreRequired(String column) {
        String i = invoice(jdbc, m());
        String p = payment(jdbc, i, m());
        String s = subscription(jdbc, m("source", "'MANUAL'", "provider", "null", "price_id", "null"));
        assertThatThrownBy(() -> withGuardOff(() -> period(jdbc, s, i, p, m(column, "null")))).hasMessageContaining(column);
    }

    @Test
    void identifiersAreUnique() throws Exception {
        String[] a = activated();
        assertThatThrownBy(() -> invoice(jdbc, m("id", lit(a[1])))).hasMessageContaining("pk_billing_invoice");
        assertThatThrownBy(() -> payment(jdbc, a[1], m("id", lit(a[2])))).hasMessageContaining("pk_payment");
        String pid = jdbc.queryForObject("select id from subscription_period where subscription_id = ?", String.class, a[0]);
        String i = invoice(jdbc, m());
        String p = payment(jdbc, i, m());
        assertThatThrownBy(() -> withGuardOff(() -> period(jdbc, a[0], i, p, m("id", lit(pid), "period_start", END, "period_end", NEXT_END)))).hasMessageContaining("pk_subscription_period");
    }

    // ---- S1-08B

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(dataSource).schemas(DEV).locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("1").target(MigrationVersion.fromVersion(target)).load();
    }

    private void legacyAt(String version) throws Exception {
        jdbc.execute("create schema " + DEV);
        String ddl = new ClassPathResource("db/legacy/ddl-auto-schema.sql").getContentAsString(StandardCharsets.UTF_8);
        try (Connection c = dataSource.getConnection()) {
            c.createStatement().execute("set search_path to " + DEV);
            for (String statement : ddl.split(";")) {
                String sql = statement.lines().filter(l -> !l.strip().startsWith("--")).reduce("", (a, b) -> a + "\n" + b).strip();
                if (!sql.isEmpty()) c.createStatement().execute(sql);
            }
            c.createStatement().execute("set search_path to public");
        }
        flyway(version).migrate();
    }

    private void seedDev(String source, String status) {
        String d = DEV + ".";
        jdbc.update("insert into " + d + "users (id, email, full_name, password_hash, role, status, \"createdAt\", \"updatedAt\") values ('u1', 'u1@example.test', 'x', 'x', 'VENUE', 'ACTIVE', now(), now())");
        jdbc.update("insert into " + d + "business (id, owner_user_id, name, business_phone, street_address, city, state, zipcode, custom_subdomain, currency, open24_7, \"createdAt\", \"updatedAt\") values ('b1', 'u1', 'B', '1', 'r', 'c', 's', '', 's1', 'ETB', true, now(), now())");
        jdbc.update("insert into " + d + "monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) values (1, 'SETTING_2', true, true, true, now(), now(), 'u1')");
        jdbc.update("insert into " + d + "business_subscription (id, business_id, plan_code, status, config_version, source, created_at, created_by_user_id, updated_at, updated_by_user_id, row_version) values ('s1', 'b1', 'MODEL_4', ?, 1, ?, now(), 'u1', now(), 'u1', 3)", status, source);
    }

    private List<Map<String, Object>> rows(String table) {
        return jdbc.queryForList("select * from " + DEV + "." + table + " order by 1");
    }

    @Test
    void aV23_1DatabaseUpgradesKeepingEveryRow() throws Exception {
        legacyAt("23.1");
        seedDev("MANUAL", "ACTIVE");
        List<Map<String, Object>> subs = rows("business_subscription"), business = rows("business");
        assertThat(flyway("23.2").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(rows("business_subscription")).isEqualTo(subs);
        assertThat(rows("business")).isEqualTo(business);
        flyway("23.2").validate();
        assertThat(flyway("23.2").migrate().migrationsExecuted).isZero();
    }

    @Test
    void theMigrationRefusesExistingProviderRows() throws Exception {
        legacyAt("23.1");
        jdbc.execute("alter table " + DEV + ".business_subscription disable trigger business_subscription_refuse_provider");
        seedDev("PROVIDER", "ACTIVE");
        assertThatThrownBy(() -> flyway("23.2").migrate()).hasMessageContaining("V23.2 refused: PROVIDER subscriptions exist without verified payment evidence");
    }

    @Test
    void theV20RefusalTriggerIsGoneAndTheEvidenceTriggerIsDeferred() {
        assertThat(jdbc.queryForObject("select count(*) from pg_trigger where tgname = 'business_subscription_refuse_provider'", Integer.class)).isZero();
        assertThat(jdbc.queryForMap("select tgdeferrable, tginitdeferred from pg_trigger where tgname = 'provider_row_has_evidence'"))
                .containsEntry("tgdeferrable", true).containsEntry("tginitdeferred", true);
    }

    @Test
    void nothingIsSeeded() {
        for (String table : List.of("billing_invoice", "payment", "subscription_period")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).as(table).isZero();
        }
    }
}
