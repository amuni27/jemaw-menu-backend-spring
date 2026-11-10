package com.agafari.com.billing;

import com.agafari.com.billing.port.BillingStrategy;
import com.agafari.com.billing.port.CheckoutKind;
import com.agafari.com.billing.port.PaymentProviderClient;
import com.agafari.com.billing.port.ProviderCapabilities;
import com.agafari.com.billing.port.ProviderTimeoutException;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.config.BillingFlags;
import com.agafari.com.jobs.PermanentJobException;
import com.agafari.com.jobs.ProviderCallGuard;
import com.agafari.com.jobs.TransientJobException;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.entity.MonetizationConfig;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.jpa.repository.MonetizationConfigRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.subscription.EvidenceInvalidException;
import com.agafari.com.subscription.ExpectedSubscriptionChangedException;
import com.agafari.com.subscription.IllegalTransitionException;
import com.agafari.com.subscription.PaidActivation;
import com.agafari.com.subscription.PeriodExtension;
import com.agafari.com.subscription.SubscriptionLifecycle;
import com.agafari.com.subscription.TransitionReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AGF SUB-R4-10 (DD §8.2, §8.3, §10.1): turns one stored provider event into exactly one durable outcome. The only writer of
 * the ledger and the only builder of {@link VerifiedPaymentRef}; called only by {@link InboxProcessHandler}.
 *
 * <ol>
 *   <li>Outside any transaction: re-fetch the payment from the provider (the event body is never evidence), read the
 *   provider's capabilities, and match the payment to a checkout by its verified metadata (PO ruling R4). Missing or
 *   conflicting evidence is quarantined; ownership is never inferred.</li>
 *   <li>One transaction under the configuration and restaurant locks: {@code materialize}, load the committed facts, run
 *   {@link PaymentOutcomeMatrix}, and write the ledger row, lifecycle change, checkout status, exception, refund request and
 *   event status together.</li>
 *   <li>If the lifecycle refuses the evidence, that transaction rolls back and a new one records the verified charge with
 *   an exception: nothing is activated or refunded.</li>
 * </ol>
 * A pending provider outcome is unresolved, never a failure (PO D2): the job retries it. A definite decline records nothing
 * financial (R7a). Real activation stays blocked by {@link ProviderActivationReadiness}.
 */
@Slf4j
@Service
public class PaymentOutcomeService implements com.agafari.com.subscription.HeldFunding {

    public enum Result { PROCESSED, QUARANTINED, DECLINED, ALREADY_DONE }

    /** DD §2.4: the lifecycle refused the evidence. */
    public static final String EVIDENCE_INVALID = "EVIDENCE_INVALID";
    /** PO ruling R5: a re-fetch contradicts the immutable ledger row of the same payment. */
    public static final String LEDGER_FACTS_MISMATCH = "LEDGER_FACTS_MISMATCH";

    static final String VERIFICATION = "SIGNED_WEBHOOK+API_FETCH";
    static final String RECONCILIATION_VERIFICATION = "RECONCILIATION_API_FETCH";
    private static final String TARGET_EXCEPTION = "BILLING_EXCEPTION";
    private static final String EXCEPTION_OPENED = "BILLING_EXCEPTION_OPENED";
    private static final int MAX_ERROR = 1000;

    /** @param replay an admin replay: an outcome already noted on the event is kept unless a new one replaces it */
    /** @param internal an internal reconciliation event (SUB-R4-14, G4): its own payload names the payment; the evidence is still the re-fetch */
    private record Event(String id, String provider, String payload, String status, boolean replay, boolean internal) {

        /** V23.4: the approved method for how this charge was verified. */
        String verification() {
            return internal ? RECONCILIATION_VERIFICATION : VERIFICATION;
        }
    }

    /** @param scheduledChangeId AGF SUB-R4-15: the exact change a DOWNGRADE_RENEWAL checkout was made for (V23.5) */
    private record Checkout(String id, String businessId, String priceId, int configVersion, CheckoutKind kind, String replaces, String status,
                            Instant cancelRequestedAt, Instant expiresAt, Money expected, String scheduledChangeId) {
    }

    /** The lifecycle refused what the matrix allowed; recorded in a transaction of its own. */
    private static final class Refused extends RuntimeException {
        final String code;
        final String reason;

        Refused(String code, String reason, Throwable cause) {
            super(code + ": " + reason, cause);
            this.code = code;
            this.reason = reason;
        }
    }

    private final JdbcTemplate jdbc;
    private final BillingStrategyRegistry registry;
    private final MonetizationLocks locks;
    private final SubscriptionLifecycle lifecycle;
    private final BusinessSubscriptionRepository subscriptions;
    private final MonetizationConfigRepository configs;
    private final MonetizationClock clock;
    private final TransactionTemplate transaction;
    private final AdminAuditService audit;
    private final ObjectMapper mapper;
    private final Set<Market> enabled = ConcurrentHashMap.newKeySet();
    private final ExceptionService exceptions;
    private final RefundService refunds;

    public PaymentOutcomeService(JdbcTemplate jdbc, BillingStrategyRegistry registry, MonetizationLocks locks, SubscriptionLifecycle lifecycle,
                                 BusinessSubscriptionRepository subscriptions, MonetizationConfigRepository configs, MonetizationClock clock,
                                 PlatformTransactionManager transactions, AdminAuditService audit, ObjectMapper mapper, BillingFlags flags, ExceptionService exceptions,
                                 RefundService refunds) {
        this.exceptions = exceptions;
        this.refunds = refunds;
        this.jdbc = jdbc;
        this.registry = registry;
        this.locks = locks;
        this.lifecycle = lifecycle;
        this.subscriptions = subscriptions;
        this.configs = configs;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.audit = audit;
        this.mapper = mapper;
        // PO ruling R3: the flag alone never opens this path while activation isn't ready.
        if (ProviderActivationReadiness.ready() && flags.us().billingEnabled()) enabled.add(Market.US);
        if (ProviderActivationReadiness.ready() && flags.et().billingEnabled()) enabled.add(Market.ET);
    }

    /** Tests only: the flag itself comes from configuration, and readiness from {@link ProviderActivationReadiness}. */
    public void setBillingEnabled(Market market, boolean on) {
        if (on) enabled.add(market);
        else enabled.remove(market);
    }

    public boolean billingActive(Market market) {
        return enabled.contains(market);
    }

    public Result process(String providerEventId) {
        return run(providerEventId, false);
    }

    /**
     * AGF SUB-R4-11 (DD §8.4, R-5): an admin replay of an event that already finished. The same path as {@link #process}: a
     * fresh re-fetch, the matrix and full evidence validation. A payment already in the ledger is never applied again, and
     * the event keeps its status unless the replay reaches a new outcome. Only {@link ExceptionService} calls this.
     */
    public Result replay(String providerEventId) {
        return run(providerEventId, true);
    }

    private Result run(String providerEventId, boolean replay) {
        ProviderCallGuard.assertNoTransaction();
        Event loaded = loadEvent(providerEventId);
        if (loaded == null) throw new PermanentJobException("No such provider event " + providerEventId, null);
        Event event = new Event(loaded.id(), loaded.provider(), loaded.payload(), loaded.status(), replay, loaded.internal());
        if (!replay && !"PENDING".equals(event.status())) return Result.ALREADY_DONE;
        if (replay && "IGNORED".equals(event.status())) return Result.ALREADY_DONE;

        BillingStrategy strategy = registry.forProvider(event.provider()).filter(s -> enabled.contains(s.market())).orElse(null);
        if (strategy == null) throw unprocessed(event, "billing is not active for provider " + event.provider());
        PaymentProviderClient client = strategy.client();
        byte[] body = event.payload().getBytes(StandardCharsets.UTF_8);
        String ref = event.internal() ? internalReference(event) : client.signatureVerifier().paymentReference(body).orElse(null);
        if (ref == null) throw unprocessed(event, "the event names no payment");

        VerifiedPayment verified;
        ProviderCapabilities capabilities;
        try {
            verified = client.verifyPayment(ref);
            capabilities = client.capabilities();
        } catch (ProviderTimeoutException e) {
            note(event, "UNRESOLVED: the provider timed out");
            throw new TransientJobException("The provider timed out verifying a payment", e);
        }
        if (!ref.equals(verified.providerPaymentRef())) return quarantine(event, "VERIFICATION_FAILED: the re-fetch named another payment");
        switch (verified.status()) {
            case PENDING -> {
                note(event, "UNRESOLVED: the provider reports the payment pending");
                throw new TransientJobException("The payment outcome is unresolved", null);
            }
            case FAILED -> {
                return finishWithoutOwner(event, "FAILED", "PROVIDER_DECLINED", Result.DECLINED);
            }
            case SUCCEEDED -> {
                // continued below
            }
        }

        String mismatch = matchCheckout(event, strategy, verified);
        Checkout checkout = mismatch == null ? loadCheckout(verified.metadata().get(PaymentMetadata.CHECKOUT_ID), false) : null;
        if (checkout == null) {
            PaymentOutcome row1 = PaymentOutcomeMatrix.decide(new OutcomeFacts(PaymentVerification.VERIFIED, false, false, null, null, null, false, null, null, null, capabilities, false));
            return quarantine(event, row1.code() + ": " + (mismatch == null ? "no checkout" : mismatch));
        }
        Refused refused;
        try {
            return transaction.execute(s -> applyLocked(event, strategy, verified, capabilities, checkout.id(), checkout.businessId()));
        } catch (Refused e) {
            refused = e;
        } catch (DataIntegrityViolationException e) {
            refused = new Refused(EVIDENCE_INVALID, "DATABASE_REFUSED_AT_COMMIT", e); // the deferred evidence trigger (V23.2)
        }
        log.warn("event=billing.outcome_refused eventId={} code={} reason={}", event.id(), refused.code, refused.reason);
        Refused recorded = refused;
        return transaction.execute(s -> recordRefusal(event, strategy, verified, checkout.id(), checkout.businessId(), recorded));
    }

    // ---- outside the transaction ----

    /** AGF SUB-R4-14: the payment an internal reconciliation event names; null if it names none. */
    private String internalReference(Event event) {
        String ref = mapper.readTree(event.payload()).path("payment_ref").asString("");
        return ref.isBlank() ? null : ref;
    }

    /** PO ruling R4: null when every verified key agrees with Agafari's records; otherwise why it doesn't. */
    private String matchCheckout(Event event, BillingStrategy strategy, VerifiedPayment verified) {
        Map<String, String> m = verified.metadata();
        String checkoutId = m.get(PaymentMetadata.CHECKOUT_ID), businessId = m.get(PaymentMetadata.BUSINESS_ID);
        if (blank(checkoutId) || blank(businessId)) return "the payment carries no checkout or restaurant";
        Checkout c = loadCheckout(checkoutId, false);
        if (c == null) return "the payment names an unknown checkout";
        if (!c.businessId().equals(businessId)) return "the checkout belongs to another restaurant";
        String checkoutProvider = jdbc.queryForObject("select provider from checkout_session where id = ?", String.class, c.id());
        if (!event.provider().equals(checkoutProvider) || !strategy.provider().equals(checkoutProvider)) return "the checkout belongs to another provider";
        String recorded = jdbc.queryForObject("select provider_session_ref from checkout_session where id = ?", String.class, c.id());
        String named = m.get(PaymentMetadata.PROVIDER_SESSION_REF);
        if (recorded != null && !recorded.equals(named)) return "the provider session reference is missing or differs";
        if (recorded == null && named != null
                && jdbc.queryForObject("select count(*) from checkout_session where provider = ? and provider_session_ref = ?", Integer.class, checkoutProvider, named) > 0) {
            return "the provider session reference belongs to another checkout";
        }
        List<String> markets = jdbc.queryForList("select market from billing_account where business_id = ? and provider = ?", String.class, c.businessId(), checkoutProvider);
        if (markets.size() != 1 || !markets.get(0).equals(strategy.market().name())) return "the restaurant has no billing account in this market";
        return null;
    }

    // ---- the outcome transaction ----

    private Result applyLocked(Event event, BillingStrategy strategy, VerifiedPayment verified, ProviderCapabilities capabilities, String checkoutId, String businessId) {
        locks.configShared();
        locks.restaurant(businessId);
        if (!event.status().equals(jdbc.queryForObject("select status from provider_event where id = ? for update", String.class, event.id()))) return Result.ALREADY_DONE;
        // AGF SUB-R4-15 (boundary safeguard 1): downgrade funding is decided on the stored state, before materialize can end the row
        Checkout early = loadCheckout(checkoutId, true);
        if (early.kind() == CheckoutKind.DOWNGRADE_RENEWAL) {
            Result funded = fundDowngrade(event, strategy, verified, early, businessId);
            if (funded != null) return funded;
        }
        lifecycle.materialize(businessId);
        Checkout c = loadCheckout(checkoutId, true);
        Instant now = clock.now();
        Instant tPay = micros(verified.paidAt());

        Map<String, Object> existing = ledgerRow(strategy.provider(), verified.providerPaymentRef());
        if (existing != null) {
            List<String> differences = ledgerDifferences(existing, c, verified, tPay);
            if (!differences.isEmpty()) {
                Map<String, Object> facts = facts(event, c, verified, null);
                facts.put("ledgerDifferences", differences);
                exception(businessId, LEDGER_FACTS_MISMATCH, (String) existing.get("id"), c.id(), event.id(), null, verified, true, "OPEN", facts, now);
            }
            // One-time consumption: the outcome of this payment was recorded when its row was written.
            return finish(event, "PROCESSED", differences.isEmpty() ? null : LEDGER_FACTS_MISMATCH, Result.PROCESSED);
        }

        boolean verifiedAsExpected = verified.amount().equals(c.expected());
        Map<String, Object> price = jdbc.queryForMap("select plan_code, billing_interval, amount_minor, currency from plan_price where id = ?", c.priceId());
        String planCode = (String) price.get("plan_code");
        BusinessSubscription current = subscriptions.findCurrentByBusinessId(businessId).orElse(null);
        boolean completedByAnother = jdbc.queryForObject("select count(*) from payment p join subscription_period sp on sp.payment_id = p.id where p.checkout_session_id = ?",
                Integer.class, c.id()) > 0
                // AGF SUB-R4-15 (N6): the change this checkout was made for is already funded by another payment
                || (c.scheduledChangeId() != null && jdbc.queryForObject("select count(*) from scheduled_change where id = ? and funding_payment_id is not null",
                Integer.class, c.scheduledChangeId()) > 0);
        boolean eligible = EligibilityAtTime.eligible(history(), planCode, tPay);
        OutcomeFacts facts = new OutcomeFacts(verifiedAsExpected ? PaymentVerification.VERIFIED : PaymentVerification.FAILED, true, completedByAnother, tPay, c.expiresAt(),
                c.cancelRequestedAt(), eligible, c.replaces(), current == null ? null : current.getId(), c.kind(), capabilities, true);
        PaymentOutcome decision = PaymentOutcomeMatrix.decide(facts);
        // AGF SUB-R4-15 (boundary safeguard 2): a downgrade renewal is never an ordinary row-7 replacement. Reaching here means it
        // couldn't fund its change (deadline passed, change no longer open), so its funds go to review instead (P3, BD-17).
        boolean downgradeNotFundable = c.kind() == CheckoutKind.DOWNGRADE_RENEWAL && decision.action() == PaymentOutcome.Action.REPLACE;
        if (downgradeNotFundable) decision = new PaymentOutcome(PaymentOutcome.Action.EXCEPTION, 6, PaymentOutcome.STATE_CHANGED, PaymentOutcome.STATE_CHANGED);
        Map<String, Object> snapshot = facts(event, c, verified, decision);
        if (downgradeNotFundable) snapshot.put("downgradeNotFundable", "the scheduled change could not be funded by this payment");
        snapshot.put("eligibleAtPayment", eligible);
        snapshot.put("completedByAnotherPayment", completedByAnother);
        snapshot.put("currentSubscriptionId", current == null ? null : current.getId());

        switch (decision.action()) {
            case UNRESOLVED -> throw new IllegalStateException("An unresolved payment never reaches the outcome transaction");
            case EXCEPTION -> {
                if (decision.row() == 0) {
                    exception(businessId, decision.code(), null, c.id(), event.id(), null, verified, true, "OPEN", snapshot, now);
                } else {
                    String paymentId = insertCharge(event, strategy.provider(), verified, c, businessId, null, tPay, now);
                    exception(businessId, decision.code(), paymentId, c.id(), event.id(), null, verified, true, "OPEN", snapshot, now);
                }
                return finish(event, "PROCESSED", decision.code(), Result.PROCESSED);
            }
            case AUTO_REFUND -> {
                String paymentId = insertCharge(event, strategy.provider(), verified, c, businessId, null, tPay, now);
                String refundId = refunds.request(businessId, paymentId, decision.cause(), "outcome:" + decision.cause()); // AGF SUB-R4-13: B3 checks
                exception(businessId, decision.code(), paymentId, c.id(), event.id(), refundId, verified, true, "AUTO_HANDLING", snapshot, now);
                return finish(event, "PROCESSED", decision.code(), Result.PROCESSED);
            }
            default -> {
                apply(event, decision, strategy, verified, c, businessId, current, price, tPay, now);
                return finish(event, "PROCESSED", null, Result.PROCESSED);
            }
        }
    }

    /** Row 7: an invoice, the ledger row, then the lifecycle with a pointer to both; the database re-checks the evidence at commit. */
    private void apply(Event event, PaymentOutcome decision, BillingStrategy strategy, VerifiedPayment verified, Checkout c, String businessId, BusinessSubscription current,
                       Map<String, Object> price, Instant tPay, Instant now) {
        try {
            if (decision.action() == PaymentOutcome.Action.EXTEND && current == null) throw new ExpectedSubscriptionChangedException(); // nothing to renew
            Instant start = switch (decision.action()) {
                case EXTEND -> current.getPeriodEnd(); // PD-3 open: a renewal starts exactly at period_end (R4-09 D2)
                default -> tPay; // R8: an initial or replacement period starts at the verified payment time
            };
            if (start == null) throw new EvidenceInvalidException(EvidenceInvalidException.Code.PERIOD_MISMATCH);
            Instant end = oneInterval(start, PriceInterval.valueOf((String) price.get("billing_interval")));
            String invoiceId = UUID.randomUUID().toString();
            jdbc.update("insert into billing_invoice (id, business_id, checkout_session_id, price_id, period_start, period_end, amount_minor, currency, status) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, 'PAID')", invoiceId, businessId, c.id(), c.priceId(), Timestamp.from(start), Timestamp.from(end),
                    price.get("amount_minor"), price.get("currency"));
            String paymentId = insertCharge(event, strategy.provider(), verified, c, businessId, invoiceId, tPay, now);
            VerifiedPaymentRef ref = new VerifiedPaymentRef(paymentId, invoiceId);
            String planCode = (String) price.get("plan_code");
            PaidActivation activation = new PaidActivation(planCode, c.configVersion(), c.priceId(), strategy.provider(), null, null, start, end, ref, now);
            switch (decision.action()) {
                case ACTIVATE -> lifecycle.activatePaid(businessId, activation);
                case EXTEND -> lifecycle.renew(current.getId(), new PeriodExtension(start, end, ref, now));
                case REPLACE -> {
                    lifecycle.replacePaid(businessId, c.replaces(), activation, c.kind() == CheckoutKind.CONVERSION ? TransitionReason.CONVERTED : TransitionReason.CHANGED);
                    supersedeOpenChanges(c.replaces(), "superseded by " + c.kind()); // AGF SUB-R4-15 (AC3): in the activation's own transaction
                }
                default -> throw new IllegalStateException("Not an applying outcome: " + decision);
            }
        } catch (EvidenceInvalidException e) {
            throw new Refused(EVIDENCE_INVALID, e.code().name(), e);
        } catch (ExpectedSubscriptionChangedException | IllegalTransitionException e) {
            throw new Refused(PaymentOutcome.STATE_CHANGED, e.getClass().getSimpleName(), e);
        } catch (DataIntegrityViolationException e) {
            throw new Refused(EVIDENCE_INVALID, "DATABASE_REFUSED", e);
        }
        // A checkout whose cancel was requested keeps that state (V23.1 forbids COMPLETED with a cancel time).
        jdbc.update("update checkout_session set status = 'COMPLETED', row_version = row_version + 1 where id = ? and status in ('CREATING', 'OPEN')", c.id());
    }

    /** DD §2.4: the refused attempt rolled back; the verified charge is still recorded, with an exception and nothing else. */
    private Result recordRefusal(Event event, BillingStrategy strategy, VerifiedPayment verified, String checkoutId, String businessId, Refused refused) {
        locks.configShared();
        locks.restaurant(businessId);
        if (!event.status().equals(jdbc.queryForObject("select status from provider_event where id = ? for update", String.class, event.id()))) return Result.ALREADY_DONE;
        Checkout c = loadCheckout(checkoutId, true);
        Instant now = clock.now();
        if (ledgerRow(strategy.provider(), verified.providerPaymentRef()) != null) return finish(event, "PROCESSED", null, Result.PROCESSED);
        String paymentId = insertCharge(event, strategy.provider(), verified, c, businessId, null, micros(verified.paidAt()), now);
        Map<String, Object> facts = facts(event, c, verified, null);
        facts.put("refusal", refused.reason);
        exception(businessId, refused.code, paymentId, c.id(), event.id(), null, verified, true, "OPEN", facts, now);
        return finish(event, "PROCESSED", refused.code, Result.PROCESSED);
    }

    // ---- writes ----

    private String insertCharge(Event event, String provider, VerifiedPayment verified, Checkout c, String businessId, String invoiceId, Instant tPay, Instant now) {
        String id = UUID.randomUUID().toString();
        int inserted = jdbc.update("insert into payment (id, business_id, invoice_id, checkout_session_id, provider, provider_payment_ref, amount_minor, currency, kind, status, "
                        + "occurred_at, verified_at, verification) values (?, ?, ?, ?, ?, ?, ?, ?, 'CHARGE', 'SUCCEEDED', ?, ?, ?) "
                        + "on conflict (provider, provider_payment_ref, kind) do nothing",
                id, businessId, invoiceId, c.id(), provider, verified.providerPaymentRef(), verified.amount().minor(), verified.amount().currency().name(),
                Timestamp.from(tPay), Timestamp.from(micros(now)), event.verification());
        if (inserted == 0) throw new IllegalStateException("A ledger row appeared under the restaurant lock"); // never: checked above, under the same lock
        return id;
    }

    /** AGF SUB-R4-11: every exception is written by {@link ExceptionService}, in this transaction. */
    private void exception(String businessId, String code, String paymentId, String checkoutId, String eventId, String refundId, VerifiedPayment verified,
                           boolean funds, String status, Map<String, Object> facts, Instant now) {
        boolean money = verified.amount() != null;
        exceptions.create(businessId, code, paymentId, checkoutId, eventId, refundId, money ? verified.amount().minor() : null, money ? verified.amount().currency().name() : null,
                micros(verified.paidAt()), funds, status, facts, micros(now));
    }

    /** Only from the status the event had when this run loaded it, so a concurrent run can't finish it twice. */
    private Result finish(Event event, String status, String note, Result result) {
        String kept = note == null && event.replay() ? jdbc.queryForObject("select last_error from provider_event where id = ?", String.class, event.id()) : note;
        int updated = jdbc.update("update provider_event set status = ?, processed_at = now(), attempts = attempts + 1, last_error = ? where id = ? and status = ?",
                status, kept, event.id(), event.status());
        return updated == 1 ? result : Result.ALREADY_DONE;
    }

    /** No restaurant is established, so no exception row can be owned (it needs one): the event itself is the durable record. */
    private Result quarantine(Event event, String reason) {
        log.warn("event=billing.event_quarantined eventId={} reason={}", event.id(), reason);
        return finishWithoutOwner(event, "QUARANTINED", reason, Result.QUARANTINED);
    }

    private Result finishWithoutOwner(Event event, String status, String note, Result result) {
        return transaction.execute(s -> finish(event, status, clip(note), result));
    }

    /** Leaves the event PENDING, counts the attempt and keeps the reason visible. */
    private void note(Event event, String reason) {
        jdbc.update("update provider_event set attempts = attempts + 1, last_error = ? where id = ? and status = 'PENDING'", clip(reason), event.id());
    }

    private PermanentJobException unprocessed(Event event, String reason) {
        note(event, reason);
        return new PermanentJobException("Provider event " + event.id() + " can't be processed: " + reason, null);
    }

    // ---- reads ----

    private Event loadEvent(String id) {
        List<Event> rows = jdbc.query("select id, provider, event_id, type, payload::text as payload, status from provider_event where id = ?",
                (rs, n) -> new Event(rs.getString("id"), rs.getString("provider"), rs.getString("payload"), rs.getString("status"), false,
                        com.agafari.com.billing.webhook.InboxService.isInternal(rs.getString("event_id"), rs.getString("type"))), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Checkout loadCheckout(String id, boolean lock) {
        if (blank(id)) return null;
        List<Checkout> rows = jdbc.query("select id, business_id, price_id, config_version, kind, replaces_subscription_id, status, cancel_requested_at, expires_at, "
                        + "expected_amount_minor, expected_currency, scheduled_change_id from checkout_session where id = ?" + (lock ? " for update" : ""),
                (rs, n) -> new Checkout(rs.getString("id"), rs.getString("business_id"), rs.getString("price_id"), rs.getInt("config_version"),
                        CheckoutKind.valueOf(rs.getString("kind")), rs.getString("replaces_subscription_id"), rs.getString("status"), instant(rs.getTimestamp("cancel_requested_at")),
                        instant(rs.getTimestamp("expires_at")), new Money(rs.getLong("expected_amount_minor"), Currency.valueOf(rs.getString("expected_currency"))),
                        rs.getString("scheduled_change_id")),
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> ledgerRow(String provider, String ref) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from payment where provider = ? and provider_payment_ref = ? and kind = 'CHARGE'", provider, ref);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static List<String> ledgerDifferences(Map<String, Object> row, Checkout c, VerifiedPayment verified, Instant tPay) {
        List<String> found = new java.util.ArrayList<>();
        if (!c.businessId().equals(row.get("business_id"))) found.add("business_id");
        if (!Objects.equals(c.id(), row.get("checkout_session_id"))) found.add("checkout_session_id");
        if (((Number) row.get("amount_minor")).longValue() != verified.amount().minor()) found.add("amount_minor");
        if (!verified.amount().currency().name().equals(row.get("currency"))) found.add("currency");
        if (!((Timestamp) row.get("occurred_at")).toInstant().equals(tPay)) found.add("occurred_at");
        return found;
    }

    private List<ConfigVersionFacts> history() {
        return configs.findByVersionGreaterThan(0).stream()
                .map((MonetizationConfig v) -> new ConfigVersionFacts(v.getVersion(), v.getEffectiveFrom(), v.models().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet())))
                .toList();
    }

    /** No PII or secrets: identifiers, amounts, times and the matrix inputs and result. */
    private static Map<String, Object> facts(Event event, Checkout c, VerifiedPayment verified, PaymentOutcome decision) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("providerEventId", event.id());
        f.put("providerPaymentRef", verified.providerPaymentRef());
        f.put("checkoutId", c.id());
        f.put("checkoutKind", c.kind().name());
        f.put("checkoutStatus", c.status());
        f.put("expected", c.expected().minor() + " " + c.expected().currency());
        f.put("verified", verified.amount().minor() + " " + verified.amount().currency());
        f.put("tPay", String.valueOf(verified.paidAt()));
        f.put("expiresAt", String.valueOf(c.expiresAt()));
        f.put("cancelRequestedAt", String.valueOf(c.cancelRequestedAt()));
        f.put("replacesSubscriptionId", c.replaces());
        if (decision != null) {
            f.put("row", decision.row());
            f.put("action", decision.action().name());
            f.put("cause", decision.cause());
        }
        return f;
    }

    static Instant oneInterval(Instant start, PriceInterval interval) {
        return switch (interval) {
            case MONTH -> start.atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
            case YEAR -> start.atZone(ZoneOffset.UTC).plusYears(1).toInstant();
        };
    }

    private static Instant micros(Instant i) {
        return i == null ? null : i.truncatedTo(ChronoUnit.MICROS);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String clip(String s) {
        return s == null || s.length() <= MAX_ERROR ? s : s.substring(0, MAX_ERROR);
    }

    // ---- AGF SUB-R4-15: paid scheduled downgrades (PO D2–D4, P1–P3) ----

    static final String CHANGE_FUNDED = "SCHEDULED_CHANGE_FUNDED";
    static final String CHANGE_SUPERSEDED = "SCHEDULED_CHANGE_SUPERSEDED";

    /**
     * Funds the checkout's change from this verified payment when every rule holds, on the stored state and before
     * {@code materialize}: the change is open and unfunded; the payment matches the checkout; it was made before the checkout
     * expired or was cancelled; the target was enabled at {@code t_pay}; the stored current row is the one the change
     * replaces. Then the boundary applies it (now, if already due). A payment after the deadline is refused here explicitly
     * and goes to review, never to row 7. Returns null to let the normal matrix decide (rows 0–6 keep their precedence).
     */
    private Result fundDowngrade(Event event, BillingStrategy strategy, VerifiedPayment verified, Checkout c, String businessId) {
        Instant now = clock.now();
        Instant tPay = micros(verified.paidAt());
        if (ledgerRow(strategy.provider(), verified.providerPaymentRef()) != null) return null;
        if (!verified.amount().equals(c.expected())) return null; // row 0
        if (tPay.isAfter(c.expiresAt())) return null; // row 3
        if (c.cancelRequestedAt() != null && tPay.isAfter(c.cancelRequestedAt())) return null; // row 4
        if (c.scheduledChangeId() == null) return null;
        List<Map<String, Object>> rows = jdbc.queryForList("select * from scheduled_change where id = ? for update", c.scheduledChangeId());
        if (rows.isEmpty()) return null;
        Map<String, Object> change = rows.get(0);
        if (!List.of("PENDING", "AWAITING_PAYMENT").contains(change.get("status")) || change.get("funding_payment_id") != null) return null; // N4, N6: the matrix decides
        Map<String, Object> price = jdbc.queryForMap("select plan_code, billing_interval, amount_minor, currency from plan_price where id = ?", c.priceId());
        if (!EligibilityAtTime.eligible(history(), (String) price.get("plan_code"), tPay)) return null; // row 5
        BusinessSubscription stored = subscriptions.findCurrentByBusinessId(businessId).orElse(null);
        if (stored == null || !stored.getId().equals(change.get("from_subscription_id")) || !stored.getId().equals(c.replaces())) return null; // row 6
        Instant effectiveAt = ((Timestamp) change.get("effective_at")).toInstant();
        Instant deadline = stored.getGraceDays() != null && stored.getPeriodEnd() != null
                ? stored.getPeriodEnd().plus(java.time.Duration.ofDays(stored.getGraceDays())) : effectiveAt; // only recorded grace counts (AD-7)
        if (tPay.isAfter(deadline)) {
            // boundary safeguard 2 (N5): too late to fund, even though the old row is still stored as current
            String paymentId = insertCharge(event, strategy.provider(), verified, c, businessId, null, tPay, now);
            Map<String, Object> facts = facts(event, c, verified, null);
            facts.put("scheduledChangeId", change.get("id"));
            facts.put("deadline", deadline.toString());
            facts.put("reason", "paid after the scheduled downgrade's deadline");
            exception(businessId, PaymentOutcome.STATE_CHANGED, paymentId, c.id(), event.id(), null, verified, true, "OPEN", facts, now);
            return finish(event, "PROCESSED", PaymentOutcome.STATE_CHANGED, Result.PROCESSED);
        }
        Instant end = oneInterval(effectiveAt, PriceInterval.valueOf((String) price.get("billing_interval")));
        String invoiceId = UUID.randomUUID().toString();
        jdbc.update("insert into billing_invoice (id, business_id, checkout_session_id, price_id, period_start, period_end, amount_minor, currency, status) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, 'PAID')", invoiceId, businessId, c.id(), c.priceId(), Timestamp.from(effectiveAt), Timestamp.from(end),
                price.get("amount_minor"), price.get("currency"));
        String paymentId = insertCharge(event, strategy.provider(), verified, c, businessId, invoiceId, tPay, now);
        jdbc.update("update scheduled_change set funding_payment_id = ?, funding_invoice_id = ?, funded_at = ? where id = ? and funding_payment_id is null "
                + "and status in ('PENDING', 'AWAITING_PAYMENT')", paymentId, invoiceId, Timestamp.from(micros(now)), change.get("id"));
        audit.success(CHANGE_FUNDED, null, AdminAuditService.SYSTEM, "SCHEDULED_CHANGE", (String) change.get("id"), "payment=" + paymentId + " tPay=" + tPay + " deadline=" + deadline);
        jdbc.update("update checkout_session set status = 'COMPLETED', row_version = row_version + 1 where id = ? and status in ('CREATING', 'OPEN')", c.id());
        lifecycle.materialize(businessId); // applies it now if the boundary has passed; otherwise it stays held
        return finish(event, "PROCESSED", null, Result.PROCESSED);
    }

    /** AC3: an activation that replaces the row supersedes its open change; a held charge goes to review, in the same transaction. */
    private void supersedeOpenChanges(String replacedSubscriptionId, String reason) {
        if (replacedSubscriptionId == null) return;
        for (String changeId : jdbc.queryForList("select id from scheduled_change where from_subscription_id = ? and status in ('PENDING', 'AWAITING_PAYMENT') for update",
                String.class, replacedSubscriptionId)) {
            if (jdbc.update("update scheduled_change set status = 'SUPERSEDED' where id = ? and status in ('PENDING', 'AWAITING_PAYMENT')", changeId) == 1) {
                audit.success(CHANGE_SUPERSEDED, null, AdminAuditService.SYSTEM, "SCHEDULED_CHANGE", changeId, reason);
                release(changeId, PaymentOutcome.STATE_CHANGED, reason);
            }
        }
    }

    // ---- HeldFunding ----

    @Override
    public VerifiedPaymentRef evidence(String paymentId, String invoiceId) {
        return new VerifiedPaymentRef(paymentId, invoiceId);
    }

    @Override
    public void release(String changeId, String code, String reason) {
        List<Map<String, Object>> rows = jdbc.queryForList("select c.id as change_id, c.business_id, p.* from scheduled_change c join payment p on p.id = c.funding_payment_id where c.id = ?",
                changeId);
        if (rows.isEmpty()) return; // unfunded: nothing is held
        Map<String, Object> p = rows.get(0);
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("scheduledChangeId", changeId);
        facts.put("reason", reason);
        facts.put("providerPaymentRef", p.get("provider_payment_ref"));
        facts.put("source", "held downgrade funding");
        exceptions.create((String) p.get("business_id"), code, (String) p.get("id"), (String) p.get("checkout_session_id"), null, null, ((Number) p.get("amount_minor")).longValue(),
                (String) p.get("currency"), ((Timestamp) p.get("occurred_at")).toInstant(), true, "OPEN", facts, micros(clock.now()));
    }

    @Override
    public boolean billingActive(String businessId) {
        List<String> markets = jdbc.queryForList("select distinct market from billing_account where business_id = ?", String.class, businessId);
        String code = markets.size() == 1 ? markets.get(0) : jdbc.queryForList("select country from business where id = ?", String.class, businessId).stream().findFirst().orElse(null);
        return code != null && Market.fromCountry(code).map(enabled::contains).orElse(false);
    }
}
