package com.agafari.com.billing;

import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.InboundEvent;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.billing.webhook.InboxService;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.subscription.Actor;
import com.agafari.com.subscription.SubscriptionLifecycle;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** AGF SUB-R4-10: an ET restaurant paying through the fake provider. Amounts are fixtures, not prices (AD-10 is open). */
abstract class PaymentOutcomeTestBase extends CheckoutTestBase {

    protected static final String PROVIDER = "FAKE_ET";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired protected PaymentOutcomeService outcomes;
    @Autowired protected InboxService inbox;
    @Autowired protected SubscriptionLifecycle lifecycle;
    @Autowired protected MonetizationLocks locks;
    @Autowired protected PlatformTransactionManager transactions;

    @BeforeEach
    void enableOutcomes() {
        outcomes.setBillingEnabled(Market.ET, true);
    }

    @AfterEach
    void disableOutcomes() {
        outcomes.setBillingEnabled(Market.ET, false);
        outcomes.setBillingEnabled(Market.US, false);
    }

    protected static Instant micros(Instant i) {
        return i.truncatedTo(ChronoUnit.MICROS);
    }

    protected static Instant oneMonth(Instant start) {
        return start.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
    }

    /** An OPEN checkout written directly, so every kind can be tested; the real creation path is tested on its own. */
    protected String checkout(Tenant t, CheckoutKind kind, String replaces, String priceId, long expected, Instant expiresAt) {
        jdbc.update("insert into billing_account (id, business_id, market, provider) values (?, ?, 'ET', ?) on conflict (business_id, provider) do nothing",
                UUID.randomUUID().toString(), t.businessId(), PROVIDER);
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into checkout_session (id, business_id, owner_user_id, price_id, config_version, kind, replaces_subscription_id, provider, provider_session_ref, "
                        + "provider_session_url, status, idempotency_key, request_hash, expires_at, expected_amount_minor, expected_currency, created_at) "
                        + "values (?, ?, ?, ?, 1, ?, ?, ?, ?, 'https://fake.test/pay', 'OPEN', ?, 'hash', ?, ?, 'ETB', now())",
                id, t.businessId(), t.owner().getId(), priceId, kind.name(), replaces, PROVIDER, "sess_" + id, UUID.randomUUID().toString(), Timestamp.from(expiresAt), expected);
        return id;
    }

    protected String checkout(Tenant t, CheckoutKind kind, String replaces) {
        return checkout(t, kind, replaces, price, ET_AMOUNT, Instant.now().plus(23, ChronoUnit.HOURS));
    }

    protected Map<String, String> metadata(String checkoutId) {
        Map<String, Object> row = jdbc.queryForMap("select business_id, provider_session_ref from checkout_session where id = ?", checkoutId);
        Map<String, String> m = new HashMap<>();
        m.put(PaymentMetadata.CHECKOUT_ID, checkoutId);
        m.put(PaymentMetadata.BUSINESS_ID, (String) row.get("business_id"));
        if (row.get("provider_session_ref") != null) m.put(PaymentMetadata.PROVIDER_SESSION_REF, (String) row.get("provider_session_ref"));
        return m;
    }

    /** The provider's answer for a new payment of the checkout's expected amount, made at {@code tPay}. */
    protected String paid(String checkoutId, Instant tPay) {
        long amount = jdbc.queryForObject("select expected_amount_minor from checkout_session where id = ?", Long.class, checkoutId);
        return paid(checkoutId, tPay, new Money(amount, Currency.ETB), metadata(checkoutId));
    }

    protected String paid(String checkoutId, Instant tPay, Money amount, Map<String, String> metadata) {
        String ref = "pay_" + SEQUENCE.incrementAndGet() + "_" + checkoutId.substring(0, 8);
        fakeEt.payment(new VerifiedPayment(ref, ProviderPaymentStatus.SUCCEEDED, amount, tPay, metadata));
        return ref;
    }

    /** A signed event naming {@code paymentRef}, stored as the webhook stores it; returns the inbox row id. */
    protected String event(String paymentRef) {
        String eventId = "evt_" + SEQUENCE.incrementAndGet();
        inbox.store(PROVIDER, new InboundEvent(eventId, "payment.succeeded"), body(eventId, paymentRef));
        return jdbc.queryForObject("select id from provider_event where provider = ? and event_id = ?", String.class, PROVIDER, eventId);
    }

    protected static byte[] body(String eventId, String paymentRef) {
        return ("{\"id\":\"" + eventId + "\",\"type\":\"payment.succeeded\",\"payment_ref\":\"" + paymentRef + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    protected PaymentOutcomeService.Result process(String eventRowId) {
        return outcomes.process(eventRowId);
    }

    protected PaymentOutcomeService.Result payAndProcess(String checkoutId, Instant tPay) {
        return process(event(paid(checkoutId, tPay)));
    }

    protected String activate(Tenant t, Instant tPay) {
        String c = checkout(t, CheckoutKind.NEW, null);
        assertThat(payAndProcess(c, tPay)).isEqualTo(PaymentOutcomeService.Result.PROCESSED);
        return current(t);
    }

    protected String current(Tenant t) {
        List<String> ids = jdbc.queryForList("select id from business_subscription where business_id = ? and status <> 'EXPIRED'", String.class, t.businessId());
        return ids.isEmpty() ? null : ids.get(0);
    }

    protected Map<String, Object> eventRow(String id) {
        return jdbc.queryForMap("select status, attempts, last_error, processed_at from provider_event where id = ?", id);
    }

    protected int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    protected int payments(Tenant t) {
        return count("select count(*) from payment where business_id = ?", t.businessId());
    }

    protected int periods(Tenant t) {
        return count("select count(*) from subscription_period where business_id = ?", t.businessId());
    }

    protected int exceptions(Tenant t) {
        return count("select count(*) from billing_exception where business_id = ?", t.businessId());
    }

    protected int refunds(Tenant t) {
        return count("select count(*) from refund_request r join payment p on p.id = r.payment_id where p.business_id = ?", t.businessId());
    }

    protected int providerSubscriptions(Tenant t) {
        return count("select count(*) from business_subscription where business_id = ? and source = 'PROVIDER'", t.businessId());
    }

    protected Map<String, Object> onlyException(Tenant t) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from billing_exception where business_id = ?", t.businessId());
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    protected String checkoutStatus(String id) {
        return jdbc.queryForObject("select status from checkout_session where id = ?", String.class, id);
    }

    /**
     * AC6, per distinct charge: consumed by exactly one period, or linked by exactly one outcome exception (a refund request has
     * its own exception). {@code LEDGER_FACTS_MISMATCH} is discrepancy evidence about a charge already accounted for, not an outcome.
     */
    protected void assertEveryChargeAccountedForOnce() {
        List<Map<String, Object>> charges = jdbc.queryForList("""
                select p.id, (select count(*) from subscription_period sp where sp.payment_id = p.id) as consumed,
                       (select count(*) from billing_exception e where e.payment_id = p.id and e.code <> 'LEDGER_FACTS_MISMATCH') as exceptions,
                       (select count(*) from refund_request r where r.payment_id = p.id) as refunds,
                       (select count(*) from billing_exception e join refund_request r on r.id = e.refund_request_id where r.payment_id = p.id) as linked_refunds
                from payment p where p.kind = 'CHARGE'""");
        for (Map<String, Object> c : charges) {
            long consumed = ((Number) c.get("consumed")).longValue(), exceptions = ((Number) c.get("exceptions")).longValue();
            long refunds = ((Number) c.get("refunds")).longValue(), linked = ((Number) c.get("linked_refunds")).longValue();
            assertThat(consumed + exceptions).as("charge %s accounted for once", c.get("id")).isEqualTo(1);
            assertThat(refunds).as("refund requests of %s", c.get("id")).isLessThanOrEqualTo(1);
            assertThat(linked).as("refund of %s has its exception", c.get("id")).isEqualTo(refunds);
        }
    }

    protected void startTrial(Tenant t, Instant end) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            locks.configShared();
            locks.restaurant(t.businessId());
            lifecycle.startTrial(t.businessId(), 1, clock.now(), end, new Actor(t.owner().getId()));
        });
    }

    protected void startOwnerFree(Tenant t) {
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            locks.configShared();
            locks.restaurant(t.businessId());
            lifecycle.startOwnerFree(t.businessId(), "MODEL_2", 1, clock.now(), new Actor(t.owner().getId()));
        });
    }
}
