package com.agafari.com.billing;

import com.agafari.com.billing.port.BillingStrategy;
import com.agafari.com.billing.port.PaymentProviderClient;
import com.agafari.com.billing.port.ProviderBilling;
import com.agafari.com.billing.port.ProviderCapabilities;
import com.agafari.com.billing.port.ProviderInvoiceState;
import com.agafari.com.billing.port.ProviderSubscriptionSnapshot;
import com.agafari.com.billing.port.ProviderTimeoutException;
import com.agafari.com.billing.port.StateTrigger;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.billing.webhook.InboxService;
import com.agafari.com.jobs.ProviderCallGuard;
import com.agafari.com.jobs.TransientJobException;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.subscription.EvidenceInvalidException;
import com.agafari.com.subscription.ExpectedSubscriptionChangedException;
import com.agafari.com.subscription.IllegalTransitionException;
import com.agafari.com.subscription.LifecycleStatus;
import com.agafari.com.subscription.PeriodExtension;
import com.agafari.com.subscription.SubscriptionLifecycle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * AGF SUB-R5-03 (addendum §1–§11): provider-initiated renewals and dunning, beside the checkout path in
 * {@link PaymentOutcomeService} (which alone calls this, from {@code inbox.process}).
 *
 * <ul>
 *   <li><b>Identity</b>: the verified provider subscription alone decides the restaurant (exactly one PROVIDER row holds it).
 *   The original checkout identity, metadata, emails and amounts are never used for ownership (§37 Q7/F2).</li>
 *   <li><b>Evidence</b>: the invoice re-fetch (PO U1); the event body only says what to re-fetch. Every decision is made on
 *   the re-fetched current state, never on an event's type or order.</li>
 *   <li><b>One transaction</b> under the configuration and restaurant locks per outcome, re-checking identity inside it; the
 *   renewal rows ({@link RenewalOutcomeMatrix}) decide; a lifecycle refusal rolls back and is recorded with an exception.</li>
 *   <li><b>Grace</b> (AD-7): the payment time decides at the boundary; Stripe's subscription status never extends access.</li>
 * </ul>
 * Real activation stays blocked by {@link ProviderActivationReadiness}.
 */
@Slf4j
@Service
public class RenewalOutcomes {

    /** The stored event, as {@link PaymentOutcomeService} loaded it. */
    public record EventRef(String id, String provider, String status, boolean replay, String verification) {
    }

    static final String REVIEW_REQUIRED = "PROVIDER_SUBSCRIPTION_REVIEW_REQUIRED";
    static final String CANCELLED_WITH_PERIOD_PAID = "PROVIDER_SUBSCRIPTION_CANCELLED_RECORDED";
    static final String DELETION_RECORDED = "PROVIDER_SUBSCRIPTION_DELETION_RECORDED";
    private static final String TARGET_PROVIDER_SUBSCRIPTION = "PROVIDER_SUBSCRIPTION";
    private static final Duration INTERVAL_TOLERANCE = Duration.ofDays(1);
    private static final int MAX_ERROR = 1000;

    /** The lifecycle refused what the rows allowed; recorded in a transaction of its own. */
    private static final class Refused extends RuntimeException {
        final String code;
        final String reason;

        Refused(String code, String reason, Throwable cause) {
            super(code + ": " + reason, cause);
            this.code = code;
            this.reason = reason;
        }
    }

    /** R6: an earlier invoice is still missing; the job retries until the grace end. */
    private static final class Retry extends RuntimeException {
        Retry(String reason) {
            super(reason);
        }
    }

    private final JdbcTemplate jdbc;
    private final MonetizationLocks locks;
    private final SubscriptionLifecycle lifecycle;
    private final BusinessSubscriptionRepository subscriptions;
    private final MonetizationClock clock;
    private final TransactionTemplate transaction;
    private final AdminAuditService audit;
    private final ExceptionService exceptions;
    private final RefundService refunds;
    private final InboxService inbox;

    public RenewalOutcomes(JdbcTemplate jdbc, MonetizationLocks locks, SubscriptionLifecycle lifecycle, BusinessSubscriptionRepository subscriptions, MonetizationClock clock,
                           PlatformTransactionManager transactions, AdminAuditService audit, ExceptionService exceptions, RefundService refunds, InboxService inbox) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.lifecycle = lifecycle;
        this.subscriptions = subscriptions;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.audit = audit;
        this.exceptions = exceptions;
        this.refunds = refunds;
        this.inbox = inbox;
    }

    // ==== renewal payments (§1–§3, §6, §7, §10) ====

    /** A verified, successful {@code subscription_cycle} invoice. Called outside any transaction, after the re-fetch. */
    public PaymentOutcomeService.Result applyPayment(EventRef e, BillingStrategy strategy, VerifiedPayment verified, ProviderCapabilities capabilities) {
        ProviderCallGuard.assertNoTransaction();
        ProviderBilling b = verified.billing();
        String businessId = b.subscriptionRef() == null ? null : ownerOf(strategy.provider(), b.subscriptionRef());
        if (businessId == null) return quarantine(e, RenewalOutcome.UNKNOWN_SUBSCRIPTION + ": the provider subscription is no Agafari PROVIDER subscription");
        Refused refused;
        try {
            return transaction.execute(s -> applyLocked(e, strategy, verified, capabilities, businessId));
        } catch (Retry r) {
            note(e, "UNRESOLVED: " + RenewalOutcome.PENDING_ORDER + ": " + r.getMessage());
            throw new TransientJobException("A renewal for a later period waits for the missing one", null);
        } catch (Refused r) {
            refused = r;
        } catch (DataIntegrityViolationException x) {
            refused = new Refused(PaymentOutcomeService.EVIDENCE_INVALID, "DATABASE_REFUSED_AT_COMMIT", x);
        }
        log.warn("event=billing.renewal_refused eventId={} code={} reason={}", e.id(), refused.code, refused.reason);
        Refused recorded = refused;
        return transaction.execute(s -> recordRefusal(e, strategy, verified, businessId, recorded));
    }

    private PaymentOutcomeService.Result applyLocked(EventRef e, BillingStrategy strategy, VerifiedPayment verified, ProviderCapabilities capabilities, String businessId) {
        locks.configShared();
        locks.restaurant(businessId);
        if (!e.status().equals(jdbc.queryForObject("select status from provider_event where id = ? for update", String.class, e.id()))) return PaymentOutcomeService.Result.ALREADY_DONE;
        ProviderBilling b = verified.billing();
        BusinessSubscription row = rowOf(strategy.provider(), b.subscriptionRef(), businessId);
        if (row == null) return finish(e, "QUARANTINED", RenewalOutcome.UNKNOWN_SUBSCRIPTION + ": the subscription changed owner", PaymentOutcomeService.Result.QUARANTINED);
        Instant now = micros(clock.now());
        Instant tPay = micros(verified.paidAt());
        // §6: the payment time decides at the grace boundary; a late payment first lets the stored row reach its real state
        if (row.isCurrent() && !SubscriptionLifecycle.paidWithinGrace(row, tPay)) {
            lifecycle.materialize(businessId);
            row = subscriptions.findById(row.getId()).orElseThrow();
        }

        boolean alreadyApplied = ledgerRow(strategy.provider(), verified.providerPaymentRef()) != null;
        Map<String, Object> price = row.getPriceId() == null ? null
                : jdbc.queryForList("select plan_code, market, billing_interval, amount_minor, currency, provider_price_ref from plan_price where id = ?", row.getPriceId()).stream()
                .findFirst().orElse(null);
        List<Map<String, Object>> accounts = jdbc.queryForList("select market, provider_customer_ref from billing_account where business_id = ? and provider = ?", businessId,
                strategy.provider());
        String storedCustomer = accounts.size() == 1 ? (String) accounts.get(0).get("provider_customer_ref") : null;
        String market = accounts.size() == 1 ? (String) accounts.get(0).get("market") : null;
        Instant start = b.periodStart() == null ? null : micros(b.periodStart());
        Instant end = b.periodEnd() == null ? null : micros(b.periodEnd());
        Instant graceEnd = SubscriptionLifecycle.graceEnd(row);
        boolean priceMatches = price != null && b.priceRef() != null && b.priceRef().equals(price.get("provider_price_ref")) && b.interval() != null
                && b.interval().name().equals(price.get("billing_interval")) && verified.amount().minor() == ((Number) price.get("amount_minor")).longValue()
                && verified.amount().currency().name().equals(price.get("currency")) && Objects.equals(market, price.get("market"));
        boolean taken = start != null && jdbc.queryForObject("select count(*) from subscription_period where subscription_id = ? and period_start = ?", Integer.class, row.getId(),
                Timestamp.from(start)) > 0;
        boolean intervalMatches = start != null && end != null && b.interval() != null
                && Duration.between(PaymentOutcomeService.oneInterval(start, b.interval()), end).abs().compareTo(INTERVAL_TOLERANCE) <= 0;
        RenewalFacts facts = new RenewalFacts(true, alreadyApplied, storedCustomer != null && storedCustomer.equals(b.customerRef()), b.qualifying(), priceMatches, taken,
                start != null && start.equals(row.getPeriodEnd()), start != null && row.getPeriodEnd() != null && start.isAfter(row.getPeriodEnd()),
                graceEnd != null && now.isBefore(graceEnd), intervalMatches, !row.isCurrent(), row.getEndedAt() != null && tPay.isAfter(row.getEndedAt()), capabilities, true);
        RenewalOutcome decision = RenewalOutcomeMatrix.decide(facts);
        Map<String, Object> snapshot = facts(e, verified, row, decision, storedCustomer);

        return switch (decision.action()) {
            case ALREADY_DONE -> finish(e, "PROCESSED", null, PaymentOutcomeService.Result.PROCESSED); // one-time consumption (R2)
            case QUARANTINE -> finish(e, "QUARANTINED", clip(decision.code()), PaymentOutcomeService.Result.QUARANTINED);
            case RETRY -> throw new Retry("the period starting " + start + " follows " + row.getPeriodEnd() + ", which isn't paid yet");
            case EXCEPTION -> {
                String paymentId = insertCharge(e, strategy.provider(), verified, businessId, null, tPay, now);
                exception(businessId, decision.code(), paymentId, e.id(), null, verified, "OPEN", snapshot, now);
                if ("R8".equals(decision.row())) review(b, paymentId, "paid after the row ended (" + decision.cause() + ")");
                yield finish(e, "PROCESSED", decision.code(), PaymentOutcomeService.Result.PROCESSED);
            }
            case AUTO_REFUND -> {
                String paymentId = insertCharge(e, strategy.provider(), verified, businessId, null, tPay, now);
                String refundId = refunds.request(businessId, paymentId, decision.cause(), "renewal:" + decision.cause()); // B3 checks
                exception(businessId, decision.code(), paymentId, e.id(), refundId, verified, "AUTO_HANDLING", snapshot, now);
                if ("R8".equals(decision.row())) review(b, paymentId, "paid after the row ended; refund requested");
                yield finish(e, "PROCESSED", decision.code(), PaymentOutcomeService.Result.PROCESSED);
            }
            case EXTEND -> {
                extend(e, strategy, verified, row, price, start, end, tPay, now, businessId);
                yield finish(e, "PROCESSED", null, PaymentOutcomeService.Result.PROCESSED);
            }
        };
    }

    /** R9: the invoice and the charge, then the lifecycle renews the same row with a pointer to both (the database re-checks at commit). */
    private void extend(EventRef e, BillingStrategy strategy, VerifiedPayment verified, BusinessSubscription row, Map<String, Object> price, Instant start, Instant end,
                        Instant tPay, Instant now, String businessId) {
        try {
            String invoiceId = UUID.randomUUID().toString();
            jdbc.update("insert into billing_invoice (id, business_id, checkout_session_id, price_id, period_start, period_end, amount_minor, currency, status, provider_invoice_ref) "
                            + "values (?, ?, null, ?, ?, ?, ?, ?, 'PAID', ?)", invoiceId, businessId, row.getPriceId(), Timestamp.from(start), Timestamp.from(end),
                    price.get("amount_minor"), price.get("currency"), verified.billing().invoiceRef());
            String paymentId = insertCharge(e, strategy.provider(), verified, businessId, invoiceId, tPay, now);
            lifecycle.renew(row.getId(), new PeriodExtension(start, end, new VerifiedPaymentRef(paymentId, invoiceId), now), tPay);
        } catch (EvidenceInvalidException x) {
            throw new Refused(PaymentOutcomeService.EVIDENCE_INVALID, x.code().name(), x);
        } catch (ExpectedSubscriptionChangedException | IllegalTransitionException x) {
            throw new Refused(PaymentOutcome.STATE_CHANGED, x.getClass().getSimpleName(), x);
        } catch (DataIntegrityViolationException x) {
            throw new Refused(PaymentOutcomeService.EVIDENCE_INVALID, "DATABASE_REFUSED", x);
        }
    }

    /** DD §2.4: the refused attempt rolled back; the verified charge is still recorded, with an exception and nothing else. */
    private PaymentOutcomeService.Result recordRefusal(EventRef e, BillingStrategy strategy, VerifiedPayment verified, String businessId, Refused refused) {
        locks.configShared();
        locks.restaurant(businessId);
        if (!e.status().equals(jdbc.queryForObject("select status from provider_event where id = ? for update", String.class, e.id()))) return PaymentOutcomeService.Result.ALREADY_DONE;
        Instant now = micros(clock.now());
        if (ledgerRow(strategy.provider(), verified.providerPaymentRef()) != null) return finish(e, "PROCESSED", null, PaymentOutcomeService.Result.PROCESSED);
        String paymentId = insertCharge(e, strategy.provider(), verified, businessId, null, micros(verified.paidAt()), now);
        Map<String, Object> facts = facts(e, verified, null, null, null);
        facts.put("refusal", refused.reason);
        exception(businessId, refused.code, paymentId, e.id(), null, verified, "OPEN", facts, now);
        review(verified.billing(), paymentId, "renewal refused: " + refused.code);
        return finish(e, "PROCESSED", refused.code, PaymentOutcomeService.Result.PROCESSED);
    }

    // ==== state events: failure, past_due, deletion (§4, §5, §8) ====

    /** A verified state event; the re-fetched invoice or subscription decides. Called outside any transaction. */
    public PaymentOutcomeService.Result applyState(EventRef e, BillingStrategy strategy, StateTrigger trigger) {
        ProviderCallGuard.assertNoTransaction();
        PaymentProviderClient client = strategy.client();
        try {
            return switch (trigger.kind()) {
                case INVOICE -> invoiceState(e, strategy, client.invoiceState(trigger.ref()));
                case SUBSCRIPTION -> subscriptionState(e, strategy, client, client.subscriptionState(trigger.ref()));
            };
        } catch (ProviderTimeoutException x) {
            note(e, "UNRESOLVED: the provider timed out");
            throw new TransientJobException("The provider timed out re-fetching a renewal state", x);
        }
    }

    /** §5: a renewal attempt failed: PAST_DUE from {@code period_end}, once; a paid invoice makes the event stale. */
    private PaymentOutcomeService.Result invoiceState(EventRef e, BillingStrategy strategy, ProviderInvoiceState inv) {
        if (inv.livemode()) return quarantine(e, "VERIFICATION_FAILED: a live-mode invoice is refused while the R5 gates are open");
        if (!inv.renewal()) return ignore(e, "not a renewal invoice");
        if (inv.status() == ProviderInvoiceState.Status.PAID) return ignore(e, "stale: the re-fetched invoice is paid; its payment decides");
        if (inv.status() != ProviderInvoiceState.Status.OPEN || !inv.attempted()) return ignore(e, "the invoice is " + inv.status() + (inv.attempted() ? "" : ", not attempted") + "; grace governs");
        String businessId = ownerOf(strategy.provider(), inv.subscriptionRef());
        if (businessId == null) return quarantine(e, RenewalOutcome.UNKNOWN_SUBSCRIPTION + ": the provider subscription is no Agafari PROVIDER subscription");
        return transaction.execute(s -> {
            locks.configShared();
            locks.restaurant(businessId);
            if (!e.status().equals(jdbc.queryForObject("select status from provider_event where id = ? for update", String.class, e.id()))) return PaymentOutcomeService.Result.ALREADY_DONE;
            lifecycle.materialize(businessId);
            BusinessSubscription row = rowOf(strategy.provider(), inv.subscriptionRef(), businessId);
            if (row == null) return finish(e, "QUARANTINED", RenewalOutcome.UNKNOWN_SUBSCRIPTION, PaymentOutcomeService.Result.QUARANTINED);
            if (!row.isCurrent()) return finish(e, "IGNORED", "the subscription has ended", PaymentOutcomeService.Result.PROCESSED);
            if (!Objects.equals(storedCustomer(businessId, strategy.provider()), inv.customerRef())) {
                return finish(e, "QUARANTINED", RenewalOutcome.CUSTOMER_MISMATCH + ": the invoice's customer isn't the restaurant's", PaymentOutcomeService.Result.QUARANTINED);
            }
            if (inv.periodStart() == null || !micros(inv.periodStart()).equals(row.getPeriodEnd())) return finish(e, "IGNORED", "not the row's next period", PaymentOutcomeService.Result.PROCESSED);
            Instant now = micros(clock.now());
            if (now.isBefore(row.getPeriodEnd())) return finish(e, "IGNORED", "the period hasn't ended in Agafari's time; period end decides", PaymentOutcomeService.Result.PROCESSED);
            if (LifecycleStatus.of(row.getStatus()) == LifecycleStatus.PAST_DUE) return finish(e, "IGNORED", "already past due", PaymentOutcomeService.Result.PROCESSED);
            lifecycle.markPastDue(row.getId(), row.getPeriodEnd(), now);
            return finish(e, "PROCESSED", null, PaymentOutcomeService.Result.PROCESSED);
        });
    }

    /** §8: deletion and status changes. A paid last invoice is applied first; a cancelled period that was paid is only recorded (R5-04). */
    private PaymentOutcomeService.Result subscriptionState(EventRef e, BillingStrategy strategy, PaymentProviderClient client, ProviderSubscriptionSnapshot snap) {
        if (snap.livemode()) return quarantine(e, "VERIFICATION_FAILED: a live-mode subscription is refused while the R5 gates are open");
        String businessId = ownerOf(strategy.provider(), snap.subscriptionRef());
        if (businessId == null) return quarantine(e, RenewalOutcome.UNKNOWN_SUBSCRIPTION + ": the provider subscription is no Agafari PROVIDER subscription");
        switch (snap.status()) {
            case PAST_DUE, UNPAID -> {
                return snap.latestInvoiceRef() == null ? ignore(e, "no latest invoice") : invoiceState(e, strategy, client.invoiceState(snap.latestInvoiceRef()));
            }
            case CANCELED, INCOMPLETE_EXPIRED -> {
                // §4: "a paid invoice for the next period is applied first if valid"
                if (snap.latestInvoiceRef() != null) {
                    ProviderInvoiceState last = client.invoiceState(snap.latestInvoiceRef());
                    if (last.renewal() && last.status() == ProviderInvoiceState.Status.PAID && ledgerRow(strategy.provider(), last.invoiceRef()) == null) {
                        inbox.storeInternal(strategy.provider(), last.invoiceRef());
                        note(e, "UNRESOLVED: the subscription's last renewal invoice is paid; it is applied first");
                        throw new TransientJobException("The deletion waits for the paid renewal invoice", null);
                    }
                }
                return transaction.execute(s -> deletionLocked(e, strategy, snap, businessId));
            }
            default -> {
                return ignore(e, "subscription " + snap.status() + ": renewal payments decide");
            }
        }
    }

    private PaymentOutcomeService.Result deletionLocked(EventRef e, BillingStrategy strategy, ProviderSubscriptionSnapshot snap, String businessId) {
        locks.configShared();
        locks.restaurant(businessId);
        if (!e.status().equals(jdbc.queryForObject("select status from provider_event where id = ? for update", String.class, e.id()))) return PaymentOutcomeService.Result.ALREADY_DONE;
        lifecycle.materialize(businessId);
        BusinessSubscription row = rowOf(strategy.provider(), snap.subscriptionRef(), businessId);
        if (row == null) return finish(e, "QUARANTINED", RenewalOutcome.UNKNOWN_SUBSCRIPTION, PaymentOutcomeService.Result.QUARANTINED);
        if (!row.isCurrent()) {
            audit.success(DELETION_RECORDED, null, AdminAuditService.SYSTEM, TARGET_PROVIDER_SUBSCRIPTION, snap.subscriptionRef(), "the Agafari subscription had already ended");
            return finish(e, "IGNORED", "the subscription has already ended", PaymentOutcomeService.Result.PROCESSED);
        }
        if (!Objects.equals(storedCustomer(businessId, strategy.provider()), snap.customerRef())) {
            return finish(e, "QUARANTINED", RenewalOutcome.CUSTOMER_MISMATCH + ": the subscription's customer isn't the restaurant's", PaymentOutcomeService.Result.QUARANTINED);
        }
        Instant now = micros(clock.now());
        Instant ended = snap.endedAt() == null ? now : micros(snap.endedAt());
        if (row.getPeriodEnd() != null && ended.isBefore(row.getPeriodEnd())) {
            // D-R4: a cancellation inside a paid period is the owner's (R5-04): recorded, never given grace by R5-03
            audit.success(CANCELLED_WITH_PERIOD_PAID, null, AdminAuditService.SYSTEM, TARGET_PROVIDER_SUBSCRIPTION, snap.subscriptionRef(),
                    "subscription=" + row.getId() + " ended at " + ended + " inside the paid period; recorded only (R5-04)");
            return finish(e, "PROCESSED", "recorded only: cancelled inside a paid period (R5-04)", PaymentOutcomeService.Result.PROCESSED);
        }
        // retries exhausted on an unpaid period (D-R2): PAST_DUE, then ended at the earlier of the deletion and the grace end (D-R3)
        if (LifecycleStatus.of(row.getStatus()) == LifecycleStatus.ACTIVE) lifecycle.markPastDue(row.getId(), row.getPeriodEnd(), now);
        Instant end = earliest(ended, SubscriptionLifecycle.graceEnd(row), now);
        lifecycle.endForFailedPayment(row.getId(), end, now);
        return finish(e, "PROCESSED", null, PaymentOutcomeService.Result.PROCESSED);
    }

    // ==== reads ====

    /** The restaurant of the one PROVIDER row holding this provider subscription (addendum §3); null if none. */
    private String ownerOf(String provider, String subscriptionRef) {
        List<String> ids = jdbc.queryForList("select business_id from business_subscription where provider = ? and provider_subscription_ref = ? and source = 'PROVIDER'",
                String.class, provider, subscriptionRef);
        return ids.size() == 1 ? ids.get(0) : null;
    }

    /** Re-checked inside the locked transaction: the same row, still this restaurant's. */
    private BusinessSubscription rowOf(String provider, String subscriptionRef, String businessId) {
        List<String> ids = jdbc.queryForList("select id from business_subscription where provider = ? and provider_subscription_ref = ? and source = 'PROVIDER' and business_id = ?",
                String.class, provider, subscriptionRef, businessId);
        return ids.size() == 1 ? subscriptions.findById(ids.get(0)).orElse(null) : null;
    }

    private String storedCustomer(String businessId, String provider) {
        List<String> stored = jdbc.queryForList("select provider_customer_ref from billing_account where business_id = ? and provider = ?", String.class, businessId, provider);
        return stored.size() == 1 ? stored.get(0) : null;
    }

    private Map<String, Object> ledgerRow(String provider, String ref) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from payment where provider = ? and provider_payment_ref = ? and kind = 'CHARGE'", provider, ref);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ==== writes ====

    private String insertCharge(EventRef e, String provider, VerifiedPayment verified, String businessId, String invoiceId, Instant tPay, Instant now) {
        String id = UUID.randomUUID().toString();
        int inserted = jdbc.update("insert into payment (id, business_id, invoice_id, checkout_session_id, provider, provider_payment_ref, amount_minor, currency, kind, status, "
                        + "occurred_at, verified_at, verification) values (?, ?, ?, null, ?, ?, ?, ?, 'CHARGE', 'SUCCEEDED', ?, ?, ?) "
                        + "on conflict (provider, provider_payment_ref, kind) do nothing",
                id, businessId, invoiceId, provider, verified.providerPaymentRef(), verified.amount().minor(), verified.amount().currency().name(), Timestamp.from(tPay),
                Timestamp.from(now), e.verification());
        if (inserted == 0) throw new IllegalStateException("A ledger row appeared under the restaurant lock");
        return id;
    }

    private void exception(String businessId, String code, String paymentId, String eventId, String refundId, VerifiedPayment verified, String status, Map<String, Object> facts,
                           Instant now) {
        exceptions.create(businessId, code, paymentId, null, eventId, refundId, verified.amount().minor(), verified.amount().currency().name(), micros(verified.paidAt()), true,
                status, facts, now);
    }

    /** §7: an operator decides about the provider subscription (an automatic cancel for renewal payments isn't implemented). */
    private void review(ProviderBilling b, String paymentId, String why) {
        String target = b.subscriptionRef() != null ? b.subscriptionRef() : b.invoiceRef();
        audit.success(REVIEW_REQUIRED, null, AdminAuditService.SYSTEM, TARGET_PROVIDER_SUBSCRIPTION, target,
                clip("invoice=" + b.invoiceRef() + " payment=" + paymentId + " " + why + "; not cancelled automatically"));
    }

    /** No PII or secrets: identifiers, amounts, times and the rows' inputs and result. */
    private static Map<String, Object> facts(EventRef e, VerifiedPayment verified, BusinessSubscription row, RenewalOutcome decision, String storedCustomer) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("providerEventId", e.id());
        f.put("providerPaymentRef", verified.providerPaymentRef());
        f.put("verified", verified.amount().minor() + " " + verified.amount().currency());
        f.put("tPay", String.valueOf(verified.paidAt()));
        ProviderBilling b = verified.billing();
        f.put("providerInvoiceRef", b.invoiceRef());
        f.put("providerSubscriptionRef", b.subscriptionRef());
        f.put("providerCustomerRef", b.customerRef());
        f.put("storedCustomerRef", storedCustomer);
        f.put("providerPriceRef", b.priceRef());
        f.put("providerMoneyMovementRef", b.moneyMovementRef());
        f.put("providerPeriod", b.periodStart() + "/" + b.periodEnd());
        if (b.evidenceProblem() != null) f.put("providerBillingProblem", b.evidenceProblem());
        if (row != null) {
            f.put("subscriptionId", row.getId());
            f.put("subscriptionStatus", row.getStatus());
            f.put("periodEnd", String.valueOf(row.getPeriodEnd()));
            f.put("graceDays", row.getGraceDays());
            f.put("endedAt", String.valueOf(row.getEndedAt()));
        }
        if (decision != null) {
            f.put("renewalRow", decision.row());
            f.put("action", decision.action().name());
            f.put("cause", decision.cause());
            f.put("refund", decision.refund().name());
        }
        return f;
    }

    // ==== event status ====

    private PaymentOutcomeService.Result finish(EventRef e, String status, String note, PaymentOutcomeService.Result result) {
        String kept = note == null && e.replay() ? jdbc.queryForObject("select last_error from provider_event where id = ?", String.class, e.id()) : clip(note);
        int updated = jdbc.update("update provider_event set status = ?, processed_at = now(), attempts = attempts + 1, last_error = ? where id = ? and status = ?",
                status, kept, e.id(), e.status());
        return updated == 1 ? result : PaymentOutcomeService.Result.ALREADY_DONE;
    }

    private PaymentOutcomeService.Result quarantine(EventRef e, String reason) {
        log.warn("event=billing.renewal_quarantined eventId={} reason={}", e.id(), reason);
        return transaction.execute(s -> finish(e, "QUARANTINED", reason, PaymentOutcomeService.Result.QUARANTINED));
    }

    private PaymentOutcomeService.Result ignore(EventRef e, String reason) {
        return transaction.execute(s -> finish(e, "IGNORED", reason, PaymentOutcomeService.Result.PROCESSED));
    }

    private void note(EventRef e, String reason) {
        jdbc.update("update provider_event set attempts = attempts + 1, last_error = ? where id = ? and status = 'PENDING'", clip(reason), e.id());
    }

    private static Instant earliest(Instant... values) {
        Instant min = null;
        for (Instant v : values) if (v != null && (min == null || v.isBefore(min))) min = v;
        return min;
    }

    private static Instant micros(Instant i) {
        return i == null ? null : i.truncatedTo(ChronoUnit.MICROS);
    }

    private static String clip(String s) {
        return s == null || s.length() <= MAX_ERROR ? s : s.substring(0, MAX_ERROR);
    }
}
