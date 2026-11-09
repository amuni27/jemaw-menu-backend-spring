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

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AGF SUB-R4-03: V23.3, a violation test for every constraint. Amounts are fixtures, not prices (AD-10 is open). */
class FinancialSchemaMigrationTest extends AbstractIntegrationTest {

    private static final String DEV = "v233_dev";
    private static final List<String> REFUND_STATES = List.of("REQUESTED", "SUBMITTING", "SUBMITTED", "SUCCEEDED", "FAILED", "FAILED_DEFINITE", "UNKNOWN");
    private static final Map<String, List<String>> PATH = Map.of(
            "REQUESTED", List.of(),
            "SUBMITTING", List.of("SUBMITTING"),
            "SUBMITTED", List.of("SUBMITTING", "SUBMITTED"),
            "UNKNOWN", List.of("SUBMITTING", "UNKNOWN"),
            "FAILED_DEFINITE", List.of("SUBMITTING", "FAILED_DEFINITE"),
            "SUCCEEDED", List.of("SUBMITTING", "SUBMITTED", "SUCCEEDED"),
            "FAILED", List.of("SUBMITTING", "SUBMITTED", "FAILED"));
    private static final Set<String> ALLOWED = Set.of("REQUESTED>SUBMITTING", "SUBMITTING>SUBMITTED", "SUBMITTING>FAILED_DEFINITE", "SUBMITTING>UNKNOWN",
            "SUBMITTED>SUCCEEDED", "SUBMITTED>FAILED", "UNKNOWN>SUBMITTING", "UNKNOWN>SUBMITTED", "UNKNOWN>SUCCEEDED");

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;

    private User admin;
    private Tenant owner;
    private Tenant other;
    private String price;
    private String charge;
    private String otherCharge;
    private String checkout;
    private String otherCheckout;
    private String event;

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

    private String insert(String table, Map<String, String> defaults, Map<String, String> overrides) {
        Map<String, String> row = new LinkedHashMap<>(m("id", lit(id())));
        row.putAll(defaults);
        row.putAll(overrides);
        jdbc.update("insert into " + table + " (" + String.join(", ", row.keySet()) + ") values (" + String.join(", ", row.values()) + ")");
        return row.get("id").replace("'", "");
    }

    @BeforeEach
    void setUp() {
        dropSchema();
        admin = data.admin("fin-admin");
        owner = data.tenant("fin-owner");
        other = data.tenant("fin-other");
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        price = id();
        jdbc.update("insert into plan_price (id, plan_code, market, currency, billing_interval, amount_minor, version, valid_from) values (?, 'MODEL_4', 'ET', 'ETB', 'MONTH', 1000, 1, now())", price);
        checkout = checkoutFor(owner);
        otherCheckout = checkoutFor(other);
        charge = payment(owner.businessId(), m());
        otherCharge = payment(other.businessId(), m());
        event = insert("provider_event", m("provider", "'P1'", "event_id", lit(id()), "type", "'t'", "payload", "'{}'::jsonb"), m());
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("drop schema if exists " + DEV + " cascade");
    }

    private String checkoutFor(Tenant t) {
        return insert("checkout_session", m("business_id", lit(t.businessId()), "owner_user_id", lit(t.owner().getId()), "price_id", lit(price), "config_version", "1", "kind", "'NEW'", "provider", "'P1'",
                "status", "'EXPIRED'", "idempotency_key", lit(id()), "request_hash", "'h'", "expires_at", "now() + interval '1 hour'", "expected_amount_minor", "1000", "expected_currency", "'ETB'", "created_at", "now()"), m());
    }

    private String payment(String business, Map<String, String> o) {
        return insert("payment", m("business_id", lit(business), "provider", "'P1'", "provider_payment_ref", lit(id()), "amount_minor", "1000", "currency", "'ETB'", "kind", "'CHARGE'", "status", "'SUCCEEDED'",
                "occurred_at", "now()", "verified_at", "now()", "verification", "'SIGNED_WEBHOOK+API_FETCH'"), o);
    }

    private String refund(Map<String, String> o) {
        return insert("refund_request", m("payment_id", lit(charge), "amount_minor", "1000", "reason", "'DUPLICATE_PAYMENT'", "request_key", lit(id()), "status", "'REQUESTED'"), o);
    }

    private String exception(Map<String, String> o) {
        return insert("billing_exception", m("business_id", lit(owner.businessId()), "code", "'STATE_CHANGED'", "payment_id", lit(charge), "involves_customer_funds", "true", "facts", "'{}'::jsonb", "status", "'OPEN'", "created_at", "now()"), o);
    }

    private void move(String refund, String to) {
        jdbc.update("update refund_request set status = ?, idempotency_key = coalesce(idempotency_key, 'refund:' || id), submitted_at = coalesce(submitted_at, now()), "
                + "provider_refund_ref = case when ? in ('SUBMITTED', 'SUCCEEDED') then coalesce(provider_refund_ref, 'ref-' || id) else provider_refund_ref end where id = ?", to, to, refund);
    }

    private String refundIn(String state) {
        String r = refund(m());
        PATH.get(state).forEach(s -> move(r, s));
        return r;
    }

    // ---- billing_exception

    @ParameterizedTest
    @ValueSource(strings = {"AUTO_HANDLING", "OPEN", "IN_REVIEW"})
    void everyOpenStatusIsAllowed(String status) {
        assertThatCode(() -> exception(m("status", lit(status)))).doesNotThrowAnyException();
    }

    @Test
    void anEscalatedExceptionIsAllowedWithItsDetails() {
        assertThatCode(() -> exception(m("status", "'ESCALATED'", "escalated_at", "now()", "escalation_reason", "'r'"))).doesNotThrowAnyException();
    }

    @Test
    void anUnknownStatusIsRefused() {
        assertThatThrownBy(() -> exception(m("status", "'NONSENSE'"))).hasMessageContaining("ck_billing_exception_status");
    }

    @ParameterizedTest
    @ValueSource(strings = {"STATE_CHANGED", "EVIDENCE_INVALID", "PAYMENT_OUTCOME_PENDING_REVIEW", "ANY_FUTURE_CODE"})
    void theCodeIsExtensible(String code) {
        assertThatCode(() -> exception(m("code", lit(code)))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REFUNDED_AUTOMATICALLY", "FUNDS_HANDLED_EXTERNALLY", "NO_ACTION_REQUIRED"})
    void everyCurrentResolutionIsAllowed(String resolution) {
        assertThatCode(() -> exception(m("status", "'RESOLVED'", "resolution", lit(resolution), "resolved_by", "'a'", "resolved_at", "now()"))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVATED_UNDER_POLICY", "REFUNDED_UNDER_POLICY", "NONSENSE"})
    void policyResolutionsWaitForTheirDecisions(String resolution) {
        assertThatThrownBy(() -> exception(m("status", "'RESOLVED'", "resolution", lit(resolution), "resolved_by", "'a'", "resolved_at", "now()"))).hasMessageContaining("ck_billing_exception_resolution");
    }

    @Test
    void anExceptionIsUniquePerPaymentAndCode() {
        exception(m());
        assertThatThrownBy(() -> exception(m())).hasMessageContaining("uk_billing_exception_payment");
        assertThatCode(() -> exception(m("code", "'LATE_PAYMENT'"))).doesNotThrowAnyException();
    }

    @Test
    void anExceptionWithoutAPaymentIsUniquePerEventAndCode() {
        exception(m("payment_id", "null", "provider_event_id", lit(event)));
        assertThatThrownBy(() -> exception(m("payment_id", "null", "provider_event_id", lit(event)))).hasMessageContaining("uk_billing_exception_event");
        assertThatCode(() -> exception(m("payment_id", "null", "provider_event_id", lit(event), "code", "'UNKNOWN_REFERENCE'"))).doesNotThrowAnyException();
    }

    @Test
    void eventUniquenessAppliesOnlyWithoutAPayment() {
        exception(m("provider_event_id", lit(event)));
        assertThatCode(() -> exception(m("payment_id", lit(otherChargeOfOwner()), "provider_event_id", lit(event)))).doesNotThrowAnyException();
    }

    private String otherChargeOfOwner() {
        return payment(owner.businessId(), m());
    }

    @Test
    void concurrentDuplicatesLeaveOneException() throws Exception {
        assertThat(race(() -> exception(m()), "uk_billing_exception_payment")).isEqualTo(1);
    }

    private int race(java.util.concurrent.Callable<String> work, String constraint) throws Exception {
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        int won = 0;
        try {
            List<Future<String>> all = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                all.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return work.call();
                }));
            }
            for (Future<String> f : all) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                    won++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).hasMessageContaining(constraint);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return won;
    }

    @Test
    void everyExceptionReferenceMustExist() {
        assertThatThrownBy(() -> exception(m("business_id", "'no'", "payment_id", "null"))).hasMessageContaining("fk_billing_exception_business");
        assertThatThrownBy(() -> exception(m("payment_id", "'no'"))).hasMessageContaining("fk_billing_exception_payment");
        assertThatThrownBy(() -> exception(m("checkout_id", "'no'"))).hasMessageContaining("fk_billing_exception_checkout");
        assertThatThrownBy(() -> exception(m("provider_event_id", "'no'"))).hasMessageContaining("fk_billing_exception_event");
        assertThatThrownBy(() -> exception(m("refund_request_id", "'no'"))).hasMessageContaining("fk_billing_exception_refund");
        assertThatThrownBy(() -> exception(m("owner_user_id", "'no'", "claimed_at", "now()"))).hasMessageContaining("fk_billing_exception_owner");
    }

    @ParameterizedTest
    @ValueSource(strings = {"business_id", "code", "involves_customer_funds", "facts", "status", "created_at"})
    void anExceptionsRequiredColumnsAreRequired(String column) {
        assertThatThrownBy(() -> exception(m(column, "null"))).hasMessageContaining(column);
    }

    @Test
    void anExceptionMayStartWithoutADueDateOrAmount() {
        assertThatCode(() -> exception(m("due_at", "null", "amount_minor", "null", "currency", "null", "paid_at", "null"))).doesNotThrowAnyException();
    }

    @Test
    void anExceptionsAmountAndCurrencyGoTogether() {
        assertThatThrownBy(() -> exception(m("amount_minor", "1000"))).hasMessageContaining("ck_billing_exception_money");
        assertThatThrownBy(() -> exception(m("currency", "'ETB'"))).hasMessageContaining("ck_billing_exception_money");
        assertThatCode(() -> exception(m("amount_minor", "1000", "currency", "'ETB'"))).doesNotThrowAnyException();
    }

    @Test
    void anExceptionsAmountMustBePositiveAndCurrencyApproved() {
        assertThatThrownBy(() -> exception(m("amount_minor", "0", "currency", "'ETB'"))).hasMessageContaining("ck_billing_exception_amount");
        assertThatThrownBy(() -> exception(m("amount_minor", "1", "currency", "'EUR'"))).hasMessageContaining("ck_billing_exception_currency");
    }

    @ParameterizedTest
    @ValueSource(strings = {"resolution", "resolved_by", "resolved_at"})
    void aResolvedExceptionNeedsEveryResolutionDetail(String column) {
        Map<String, String> v = m("status", "'RESOLVED'", "resolution", "'NO_ACTION_REQUIRED'", "resolved_by", "'a'", "resolved_at", "now()");
        v.put(column, "null");
        assertThatThrownBy(() -> exception(v)).hasMessageContaining("ck_billing_exception_resolved");
    }

    @ParameterizedTest
    @ValueSource(strings = {"resolution = 'NO_ACTION_REQUIRED'", "resolved_by = 'a'", "resolved_at = now()"})
    void anOpenExceptionHasNoResolutionDetail(String set) {
        String e = exception(m());
        assertThatThrownBy(() -> jdbc.update("update billing_exception set " + set + " where id = ?", e)).hasMessageContaining("ck_billing_exception_resolved");
    }

    @ParameterizedTest
    @ValueSource(strings = {"escalated_at", "escalation_reason"})
    void anEscalationNeedsItsDetails(String column) {
        Map<String, String> v = m("status", "'ESCALATED'", "escalated_at", "now()", "escalation_reason", "'r'");
        v.put(column, "null");
        assertThatThrownBy(() -> exception(v)).hasMessageContaining("ck_billing_exception_escalated");
    }

    @Test
    void aClaimRecordsOwnerAndTimeTogether() {
        assertThatThrownBy(() -> exception(m("owner_user_id", lit(admin.getId())))).hasMessageContaining("ck_billing_exception_claim");
        assertThatThrownBy(() -> exception(m("claimed_at", "now()"))).hasMessageContaining("ck_billing_exception_claim");
    }

    @Test
    void anExceptionCantLinkAnotherRestaurantsRecords() {
        assertThatThrownBy(() -> exception(m("payment_id", lit(otherCharge)))).hasMessageContaining("must belong to the exception's restaurant");
        assertThatThrownBy(() -> exception(m("checkout_id", lit(otherCheckout)))).hasMessageContaining("must belong to the exception's restaurant");
        String foreign = refund(m("payment_id", lit(otherCharge)));
        assertThatThrownBy(() -> exception(m("refund_request_id", lit(foreign)))).hasMessageContaining("must belong to the exception's restaurant");
    }

    @ParameterizedTest
    @ValueSource(strings = {"id = 'x'", "business_id = '$OTHER'", "code = 'OTHER'", "payment_id = null", "checkout_id = '$CHECKOUT'", "provider_event_id = '$EVENT'", "amount_minor = 5", "currency = 'USD'",
            "paid_at = now()", "involves_customer_funds = false", "facts = '{\"a\":1}'::jsonb", "created_at = now() + interval '1 day'"})
    void anExceptionsFinancialFactsAreImmutable(String change) {
        String e = exception(m());
        String set = change.replace("$OTHER", other.businessId()).replace("$CHECKOUT", checkout).replace("$EVENT", event);
        assertThatThrownBy(() -> jdbc.update("update billing_exception set " + set + " where id = ?", e)).hasMessageContaining("an exception's financial facts can't be changed");
    }

    @Test
    void reviewFieldsMayChange() {
        String e = exception(m());
        assertThatCode(() -> {
            jdbc.update("update billing_exception set status = 'IN_REVIEW', owner_user_id = ?, claimed_at = now(), due_at = now() + interval '2 days', row_version = row_version + 1 where id = ?", admin.getId(), e);
            jdbc.update("update billing_exception set status = 'ESCALATED', escalated_at = now(), escalation_reason = 'r', escalated_to = 'x' where id = ?", e);
            jdbc.update("update billing_exception set status = 'RESOLVED', resolution = 'NO_ACTION_REQUIRED', resolution_note = 'n', resolved_by = 'a', resolved_at = now() where id = ?", e);
        }).doesNotThrowAnyException();
    }

    @Test
    void aRefundLinkCanBeSetOnce() {
        String e = exception(m());
        String r = refund(m());
        assertThatCode(() -> jdbc.update("update billing_exception set refund_request_id = ? where id = ?", r, e)).doesNotThrowAnyException();
        String second = refund(m());
        assertThatThrownBy(() -> jdbc.update("update billing_exception set refund_request_id = ? where id = ?", second, e)).hasMessageContaining("an exception's refund link can't be changed");
        assertThatThrownBy(() -> jdbc.update("update billing_exception set refund_request_id = null where id = ?", e)).hasMessageContaining("an exception's refund link can't be changed");
    }

    @Test
    void aLateRefundLinkMustBeTheSameRestaurant() {
        String e = exception(m());
        String foreign = refund(m("payment_id", lit(otherCharge)));
        assertThatThrownBy(() -> jdbc.update("update billing_exception set refund_request_id = ? where id = ?", foreign, e)).hasMessageContaining("must belong to the exception's restaurant");
    }

    @Test
    void aResolvedExceptionIsFinal() {
        String e = exception(m("status", "'RESOLVED'", "resolution", "'NO_ACTION_REQUIRED'", "resolved_by", "'a'", "resolved_at", "now()"));
        assertThatThrownBy(() -> jdbc.update("update billing_exception set resolution_note = 'changed' where id = ?", e)).hasMessageContaining("a resolved exception can't be changed");
    }

    @Test
    void aNewExceptionHasRowVersionZero() {
        String e = exception(m());
        assertThat(jdbc.queryForObject("select row_version from billing_exception where id = ?", Integer.class, e)).isZero();
    }

    @Test
    void anExceptionCantBeDeleted() {
        String e = exception(m());
        assertThatThrownBy(() -> jdbc.update("delete from billing_exception where id = ?", e)).hasMessageContaining("exceptions can't be removed");
    }

    // ---- refund_request

    @Test
    void aRefundStartsRequested() {
        for (String s : REFUND_STATES.subList(1, REFUND_STATES.size())) {
            assertThatThrownBy(() -> refund(m("status", lit(s), "idempotency_key", "'k'", "submitted_at", "now()", "provider_refund_ref", lit(id())))).as(s).hasMessageContaining("a refund request starts as REQUESTED");
        }
    }

    @Test
    void anUnknownRefundStatusIsRefused() {
        String r = refund(m());
        assertThatThrownBy(() -> jdbc.update("update refund_request set status = 'NONSENSE' where id = ?", r)).hasMessageContaining("can't move");
        jdbc.execute("alter table refund_request disable trigger refund_request_guard");
        try {
            assertThatThrownBy(() -> jdbc.update("update refund_request set status = 'NONSENSE' where id = ?", r)).hasMessageContaining("ck_refund_request_status");
        } finally {
            jdbc.execute("alter table refund_request enable trigger refund_request_guard");
        }
    }

    static Stream<Arguments> transitions() {
        List<Arguments> all = new ArrayList<>();
        for (String from : REFUND_STATES) for (String to : REFUND_STATES) if (!from.equals(to)) all.add(Arguments.of(from, to));
        return all.stream();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("transitions")
    void onlyTheDesignedTransitionsAreAllowed(String from, String to) {
        String r = refundIn(from);
        if (ALLOWED.contains(from + ">" + to)) assertThatCode(() -> move(r, to)).doesNotThrowAnyException();
        else assertThatThrownBy(() -> move(r, to)).hasMessageContaining("a refund request can't move");
    }

    @Test
    void aRetryKeepsTheOriginalKeyAndFirstSubmission() {
        String r = refundIn("UNKNOWN");
        assertThatThrownBy(() -> jdbc.update("update refund_request set idempotency_key = 'new' where id = ?", r)).hasMessageContaining("submission details can't be changed once set");
        assertThatThrownBy(() -> jdbc.update("update refund_request set submitted_at = now() + interval '1 day' where id = ?", r)).hasMessageContaining("submission details can't be changed once set");
        assertThatCode(() -> move(r, "SUBMITTING")).doesNotThrowAnyException();
    }

    @Test
    void aProviderRefundReferenceCantChange() {
        String r = refundIn("SUBMITTED");
        assertThatThrownBy(() -> jdbc.update("update refund_request set provider_refund_ref = 'other' where id = ?", r)).hasMessageContaining("submission details can't be changed once set");
    }

    @ParameterizedTest
    @ValueSource(strings = {"id = 'x'", "payment_id = '$OTHER'", "amount_minor = 5", "reason = 'OTHER'", "request_key = 'other'"})
    void aRefundsIdentityIsImmutable(String change) {
        String r = refund(m());
        String set = change.replace("$OTHER", otherCharge);
        assertThatThrownBy(() -> jdbc.update("update refund_request set " + set + " where id = ?", r)).hasMessageContaining("payment, amount, reason and key can't be changed");
    }

    @Test
    void aRefundCantBeDeleted() {
        String r = refund(m());
        assertThatThrownBy(() -> jdbc.update("delete from refund_request where id = ?", r)).hasMessageContaining("refund requests can't be removed");
    }

    @Test
    void aRefundIsUniquePerPaymentAndKey() {
        refund(m("request_key", "'k'"));
        assertThatThrownBy(() -> refund(m("request_key", "'k'"))).hasMessageContaining("uk_refund_request_payment_key");
        assertThatCode(() -> refund(m("request_key", "'k'", "payment_id", lit(otherCharge)))).doesNotThrowAnyException();
    }

    @Test
    void concurrentDuplicatesLeaveOneRefundRequest() throws Exception {
        assertThat(race(() -> refund(m("request_key", "'same'")), "uk_refund_request_payment_key")).isEqualTo(1);
    }

    @Test
    void aProviderRefundReferenceIsOneRefund() {
        String a = refundIn("SUBMITTING");
        String b = refundIn("SUBMITTING");
        jdbc.update("update refund_request set status = 'SUBMITTED', provider_refund_ref = 'same' where id = ?", a);
        assertThatThrownBy(() -> jdbc.update("update refund_request set status = 'SUBMITTED', provider_refund_ref = 'same' where id = ?", b)).hasMessageContaining("uk_refund_request_provider_ref");
    }

    @Test
    void aRefundMustReferToAPayment() {
        assertThatThrownBy(() -> refund(m("payment_id", "'no'"))).hasMessageContaining("fk_refund_request_payment");
    }

    static Stream<Arguments> notAVerifiedCharge() {
        return Stream.of(Arguments.of("pending", m("status", "'PENDING'")), Arguments.of("failed", m("status", "'FAILED'")), Arguments.of("no verified_at", m("verified_at", "null")),
                Arguments.of("no verification", m("verification", "null")), Arguments.of("a refund", m("kind", "'REFUND'")), Arguments.of("a chargeback", m("kind", "'CHARGEBACK'")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notAVerifiedCharge")
    void aRefundNeedsAVerifiedCharge(String name, Map<String, String> onPayment) {
        String p = payment(owner.businessId(), onPayment);
        assertThatThrownBy(() -> refund(m("payment_id", lit(p)))).hasMessageContaining("a refund needs a verified charge");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void aRefundAmountMustBePositive(String amount) {
        assertThatThrownBy(() -> refund(m("amount_minor", amount))).hasMessageContaining("ck_refund_request_amount");
    }

    @ParameterizedTest
    @ValueSource(strings = {"payment_id", "amount_minor", "reason", "request_key", "status"})
    void aRefundsRequiredColumnsAreRequired(String column) {
        assertThatThrownBy(() -> refund(m(column, "null"))).hasMessageContaining(column);
    }

    @ParameterizedTest
    @ValueSource(strings = {"idempotency_key", "submitted_at"})
    void aSubmissionNeedsItsKeyAndTime(String column) {
        String r = refund(m());
        String set = column.equals("idempotency_key") ? "submitted_at = now()" : "idempotency_key = 'k'";
        assertThatThrownBy(() -> jdbc.update("update refund_request set status = 'SUBMITTING', " + set + " where id = ?", r)).hasMessageContaining("ck_refund_request_submission");
    }

    @Test
    void aSubmittedRefundNeedsTheProviderReference() {
        String r = refundIn("SUBMITTING");
        assertThatThrownBy(() -> jdbc.update("update refund_request set status = 'SUBMITTED' where id = ?", r)).hasMessageContaining("ck_refund_request_provider_ref");
    }

    @Test
    void aRefundRequestLinksToItsOriginalCharge() {
        String r = refund(m());
        assertThat(jdbc.queryForObject("select payment_id from refund_request where id = ?", String.class, r)).isEqualTo(charge);
    }

    @Test
    void noForeignKeyCanCascadeIntoTheseTables() {
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where contype = 'f' and confdeltype <> 'a' and conrelid::regclass::text in ('billing_exception', 'refund_request')", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from pg_constraint where contype = 'f' and conrelid::regclass::text in ('billing_exception', 'refund_request')", Integer.class)).isEqualTo(7);
    }

    @Test
    void identifiersAreUnique() {
        String e = exception(m());
        assertThatThrownBy(() -> exception(m("id", lit(e), "code", "'X'"))).hasMessageContaining("pk_billing_exception");
        String r = refund(m());
        assertThatThrownBy(() -> refund(m("id", lit(r)))).hasMessageContaining("pk_refund_request");
    }

    // ---- market calendar

    private void day(String market, String date, Map<String, String> o) {
        Map<String, String> row = m("market", lit(market), "calendar_date", lit(date), "kind", "'PUBLIC_HOLIDAY'", "name", "'Holiday'", "source_note", "'official list'");
        row.putAll(o);
        jdbc.update("insert into market_calendar_day (" + String.join(", ", row.keySet()) + ") values (" + String.join(", ", row.values()) + ")");
    }

    @Test
    void aHolidayIsUniquePerMarketAndDate() {
        day("ET", "2030-01-07", m());
        assertThatThrownBy(() -> day("ET", "2030-01-07", m())).hasMessageContaining("pk_market_calendar_day");
        assertThatCode(() -> day("US", "2030-01-07", m())).doesNotThrowAnyException();
    }

    @Test
    void aHolidaysMarketAndKindMustBeKnown() {
        assertThatThrownBy(() -> day("GB", "2030-01-07", m())).hasMessageContaining("ck_market_calendar_day_market");
        assertThatThrownBy(() -> day("ET", "2030-01-07", m("kind", "'OTHER'"))).hasMessageContaining("ck_market_calendar_day_kind");
    }

    @ParameterizedTest
    @ValueSource(strings = {"market", "calendar_date", "kind", "name"})
    void aHolidaysRequiredColumnsAreRequired(String column) {
        assertThatThrownBy(() -> day("ET", "2030-01-07", m(column, "null"))).hasMessageContaining(column);
    }

    @Test
    void aHolidayCanBeCorrectedOrRemoved() {
        day("ET", "2030-01-07", m());
        assertThatCode(() -> {
            jdbc.update("update market_calendar_day set name = 'Renamed' where market = 'ET'");
            jdbc.update("delete from market_calendar_day where market = 'ET'");
        }).doesNotThrowAnyException();
    }

    @Test
    void aYearDefaultsToUnpublishedWithSaturdayAndSundayWeekends() {
        jdbc.update("insert into market_calendar_year (market, year) values ('ET', 2030)");
        assertThat(jdbc.queryForMap("select published, weekend_days from market_calendar_year where market = 'ET'")).containsEntry("published", false).containsEntry("weekend_days", "SATURDAY,SUNDAY");
    }

    @Test
    void aYearIsUniquePerMarket() {
        jdbc.update("insert into market_calendar_year (market, year) values ('ET', 2030)");
        assertThatThrownBy(() -> jdbc.update("insert into market_calendar_year (market, year) values ('ET', 2030)")).hasMessageContaining("pk_market_calendar_year");
        assertThatCode(() -> jdbc.update("insert into market_calendar_year (market, year) values ('US', 2030)")).doesNotThrowAnyException();
    }

    @Test
    void aYearsMarketMustBeKnown() {
        assertThatThrownBy(() -> jdbc.update("insert into market_calendar_year (market, year) values ('GB', 2030)")).hasMessageContaining("ck_market_calendar_year_market");
    }

    @ParameterizedTest
    @ValueSource(strings = {"FRIDAY", "FRIDAY,SATURDAY", "SUNDAY"})
    void weekendsAreConfigurable(String days) {
        assertThatCode(() -> jdbc.update("insert into market_calendar_year (market, year, weekend_days) values ('ET', 2030, ?)", days)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "SAT", "SATURDAY,", "saturday", "SATURDAY;SUNDAY"})
    void badWeekendsAreRefused(String days) {
        assertThatThrownBy(() -> jdbc.update("insert into market_calendar_year (market, year, weekend_days) values ('ET', 2030, ?)", days)).hasMessageContaining("ck_market_calendar_year_weekend");
    }

    @Test
    void aYearsColumnsAreRequired() {
        assertThatThrownBy(() -> jdbc.update("insert into market_calendar_year (market, year, published) values ('ET', 2030, null)")).hasMessageContaining("published");
        assertThatThrownBy(() -> jdbc.update("insert into market_calendar_year (market, year, weekend_days) values ('ET', 2030, null)")).hasMessageContaining("weekend_days");
        assertThatThrownBy(() -> jdbc.update("insert into market_calendar_year (market) values ('ET')")).hasMessageContaining("year");
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

    @Test
    void aV23_2DatabaseUpgradesKeepingEveryRow() throws Exception {
        legacyAt("23.2");
        String d = DEV + ".";
        jdbc.update("insert into " + d + "users (id, email, full_name, password_hash, role, status, \"createdAt\", \"updatedAt\") values ('u1', 'u1@example.test', 'x', 'x', 'VENUE', 'ACTIVE', now(), now())");
        jdbc.update("insert into " + d + "business (id, owner_user_id, name, business_phone, street_address, city, state, zipcode, custom_subdomain, currency, open24_7, \"createdAt\", \"updatedAt\") values ('b1', 'u1', 'B', '1', 'r', 'c', 's', '', 's1', 'ETB', true, now(), now())");
        jdbc.update("insert into " + d + "payment (id, business_id, provider, provider_payment_ref, amount_minor, currency, kind, status, occurred_at, verified_at, verification) values ('p1', 'b1', 'P1', 'r', 1000, 'ETB', 'CHARGE', 'SUCCEEDED', now(), now(), 'SIGNED_WEBHOOK+API_FETCH')");
        List<Map<String, Object>> payments = jdbc.queryForList("select * from " + d + "payment order by 1");
        assertThat(flyway("23.3").migrate().migrationsExecuted).isEqualTo(1);
        assertThat(jdbc.queryForList("select * from " + d + "payment order by 1")).isEqualTo(payments);
        flyway("23.3").validate();
        assertThat(flyway("23.3").migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("select version from " + d + "flyway_schema_history where version is not null order by installed_rank", String.class)).endsWith("23.2", "23.3");
    }

    @Test
    void v23_3ReferencesNoLaterTable() throws Exception {
        String sql = new ClassPathResource("db/migration/V23_3__financial_exceptions_refunds_calendar.sql").getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        assertThat(sql).doesNotContain("legacy_publish_right", "migration_run");
    }

    @Test
    void nothingIsSeeded() {
        for (String table : List.of("market_calendar_day", "market_calendar_year")) {
            assertThat(jdbc.queryForObject("select count(*) from " + table, Integer.class)).as(table).isZero();
        }
    }
}
