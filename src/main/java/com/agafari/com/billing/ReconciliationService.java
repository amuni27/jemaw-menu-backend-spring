package com.agafari.com.billing;

import com.agafari.com.billing.port.BillingStrategy;
import com.agafari.com.billing.port.ProviderPaymentStatus;
import com.agafari.com.billing.port.ProviderRecord;
import com.agafari.com.billing.port.RecordType;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.billing.webhook.InboxService;
import com.agafari.com.jobs.ProviderCallGuard;
import com.agafari.com.jobs.ProviderCallInTransactionException;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.service.MonetizationLocks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AGF SUB-R4-14, reduced scope (TA §6.7; PO G1–G5): repairs or flags divergences through the same handlers, never by itself.
 *
 * <ul>
 *   <li><b>Hourly:</b> a {@code CREATING} checkout older than 10 minutes goes through the existing idempotent recovery, which
 *   also works when its job is {@code DEAD} and refuses while a payment is under review (G3); an {@code OPEN} or
 *   {@code CANCEL_REQUESTED} checkout past its expiry becomes {@code EXPIRED} or {@code CANCELLED}. A payment found later is
 *   still judged by its own payment time.</li>
 *   <li><b>Daily:</b> each payment the provider lists becomes an internal, idempotent reconciliation event processed by
 *   {@code inbox.process} with a fresh re-fetch (G4). The listing itself is never evidence.</li>
 *   <li><b>Invariants:</b> a verified charge accounted for by nothing; a period whose invoice is no longer {@code PAID}; a
 *   ledger row the provider contradicts. Each becomes one restaurant-scoped exception of an approved code, the restaurant
 *   coming from the ledger row itself (G1). A timeout or a pending answer is not evidence and is only recorded.</li>
 * </ul>
 * Provider renewal state and invoice or subscription comparison are out of scope (G2): {@code PeriodEndJob} keeps PROVIDER
 * transitions, and a failed renewal is never inferred from missing data. Quarantined events are never replayed (G5).
 * Refund settlement is untouched.
 */
@Slf4j
@Service
public class ReconciliationService {

    public static final String CHARGE_UNACCOUNTED = "RECONCILIATION_CHARGE_UNACCOUNTED";
    public static final String PERIOD_WITHOUT_PAID_INVOICE = "RECONCILIATION_PERIOD_WITHOUT_PAID_INVOICE";
    public static final String LEDGER_UNCONFIRMED = "RECONCILIATION_LEDGER_UNCONFIRMED";
    static final String RECONCILED = "BILLING_RECONCILED";
    private static final Duration STALE_CREATING = Duration.ofMinutes(10);
    private static final Duration LOOKBACK = Duration.ofDays(3);
    private static final int BATCH = 500;

    public record HourlyResult(int checkoutsRecovered, int checkoutsRefusedUnderReview, int checkoutsExpired) {
    }

    public record DailyResult(int eventsCreated, int exceptionsCreated, int unverifiable) {
    }

    private final JdbcTemplate jdbc;
    private final BillingStrategyRegistry registry;
    private final PaymentOutcomeService outcomes;
    private final CheckoutService checkouts;
    private final InboxService inbox;
    private final ExceptionService exceptions;
    private final MonetizationLocks locks;
    private final AdminAuditService audit;
    private final TransactionTemplate transaction;

    public ReconciliationService(JdbcTemplate jdbc, BillingStrategyRegistry registry, PaymentOutcomeService outcomes, CheckoutService checkouts, InboxService inbox,
                                 ExceptionService exceptions, MonetizationLocks locks, AdminAuditService audit, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.outcomes = outcomes;
        this.checkouts = checkouts;
        this.inbox = inbox;
        this.exceptions = exceptions;
        this.locks = locks;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactions);
    }

    // ---- hourly ----

    public HourlyResult hourly(Instant now) {
        ProviderCallGuard.assertNoTransaction();
        int recovered = 0, refused = 0, expired = 0;
        for (Map<String, Object> c : jdbc.queryForList("select id, business_id, provider from checkout_session where status = 'CREATING' and created_at < ? order by created_at limit ?",
                Timestamp.from(now.minus(STALE_CREATING)), BATCH)) {
            String id = (String) c.get("id");
            if (!active((String) c.get("provider"))) continue;
            checkouts.recover(id); // idempotent; refuses under review (G3); a DEAD job's key is never reused, so this is called directly
            Map<String, Object> after = jdbc.queryForMap("select status, cancelled_reason from checkout_session where id = ?", id);
            if ("CREATING".equals(after.get("status"))) continue;
            if ("OPEN".equals(after.get("status"))) recovered++;
            if (com.agafari.com.exception.CheckoutRefusedException.PAYMENT_UNDER_REVIEW.equals(after.get("cancelled_reason"))) refused++;
            reconciled(id, "stale CREATING -> " + after.get("status") + (after.get("cancelled_reason") == null ? "" : " " + after.get("cancelled_reason")));
        }
        for (Map<String, Object> c : jdbc.queryForList("select id, business_id, provider, status from checkout_session where status in ('OPEN', 'CANCEL_REQUESTED') and expires_at < ? "
                + "order by expires_at limit ?", Timestamp.from(now), BATCH)) {
            if (!active((String) c.get("provider"))) continue;
            String id = (String) c.get("id"), from = (String) c.get("status"), to = "OPEN".equals(from) ? "EXPIRED" : "CANCELLED";
            boolean moved = Boolean.TRUE.equals(transaction.execute(s -> {
                locks.restaurant((String) c.get("business_id"));
                boolean done = jdbc.update("update checkout_session set status = ?, row_version = row_version + 1 where id = ? and status = ? and expires_at < ?", to, id, from, Timestamp.from(now)) == 1;
                if (done) audit.success(RECONCILED, null, AdminAuditService.SYSTEM, "CHECKOUT_SESSION", id, "expired " + from + " -> " + to);
                return done;
            }));
            if (moved) expired++;
        }
        return new HourlyResult(recovered, refused, expired);
    }

    // ---- daily ----

    public DailyResult daily(Instant now) {
        ProviderCallGuard.assertNoTransaction();
        int created = 0, flagged = 0, unverifiable = 0;
        Instant since = now.minus(LOOKBACK);
        for (BillingStrategy strategy : activeStrategies()) {
            List<ProviderRecord> listed;
            try {
                listed = strategy.client().listSince(since, RecordType.PAYMENT);
            } catch (ProviderCallInTransactionException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("event=billing.reconcile_listing_failed provider={} cause={}", strategy.provider(), e.getClass().getSimpleName());
                unverifiable++;
                continue;
            }
            Set<String> refs = new LinkedHashSet<>();
            for (ProviderRecord r : listed) if (r.type() == RecordType.PAYMENT && r.providerRef() != null && !r.providerRef().isBlank()) refs.add(r.providerRef());
            for (String ref : refs) {
                if (jdbc.queryForObject("select exists (select 1 from payment where provider = ? and provider_payment_ref = ? and kind = 'CHARGE')", Boolean.class, strategy.provider(), ref)) continue;
                boolean existed = jdbc.queryForObject("select exists (select 1 from provider_event where provider = ? and event_id = ?)", Boolean.class,
                        strategy.provider(), InboxService.INTERNAL_PREFIX + ref);
                inbox.storeInternal(strategy.provider(), ref);
                if (!existed) {
                    created++;
                    reconciled(ref, "listed payment queued for verification provider=" + strategy.provider());
                }
            }
        }
        flagged += unaccountedCharges();
        flagged += periodsWithoutPaidInvoice();
        int[] confirmation = confirmLedger(since);
        flagged += confirmation[0];
        unverifiable += confirmation[1];
        return new DailyResult(created, flagged, unverifiable);
    }

    /** G1: a verified charge consumed by no period and linked to no refund request or exception. */
    private int unaccountedCharges() {
        int n = 0;
        for (Map<String, Object> p : jdbc.queryForList("""
                select p.* from payment p
                where p.kind = 'CHARGE' and p.status = 'SUCCEEDED'
                  and not exists (select 1 from subscription_period sp where sp.payment_id = p.id)
                  and not exists (select 1 from billing_exception e where e.payment_id = p.id)
                  and not exists (select 1 from refund_request r where r.payment_id = p.id)
                order by p.occurred_at limit ?""", BATCH)) {
            Map<String, Object> facts = facts(p, "a verified charge is consumed by no period and linked to no refund request or exception");
            if (flag(p, CHARGE_UNACCOUNTED, true, facts)) n++;
        }
        return n;
    }

    /** G1: every PROVIDER period has one PAID invoice. */
    private int periodsWithoutPaidInvoice() {
        int n = 0;
        for (Map<String, Object> row : jdbc.queryForList("""
                select p.*, i.status as invoice_status, sp.id as period_id, sp.subscription_id from subscription_period sp
                join billing_invoice i on i.id = sp.invoice_id join payment p on p.id = sp.payment_id
                where i.status <> 'PAID' order by sp.period_start limit ?""", BATCH)) {
            Map<String, Object> facts = facts(row, "the period's invoice is not PAID");
            facts.put("invoiceStatus", row.get("invoice_status"));
            facts.put("periodId", row.get("period_id"));
            facts.put("subscriptionId", row.get("subscription_id"));
            if (flag(row, PERIOD_WITHOUT_PAID_INVOICE, false, facts)) n++;
        }
        return n;
    }

    /**
     * G1: every recent ledger charge exists at the provider as recorded. A contradiction (another payment, not succeeded, a
     * different amount, currency or payment time) is flagged; a timeout, an error or a pending answer is only recorded.
     *
     * @return {flagged, unverifiable}
     */
    private int[] confirmLedger(Instant since) {
        int flagged = 0, unverifiable = 0;
        for (Map<String, Object> p : jdbc.queryForList("select * from payment where kind = 'CHARGE' and status = 'SUCCEEDED' and verified_at >= ? order by verified_at limit ?",
                Timestamp.from(since), BATCH)) {
            BillingStrategy strategy = registry.forProvider((String) p.get("provider")).filter(s -> outcomes.billingActive(s.market())).orElse(null);
            if (strategy == null) continue;
            String ref = (String) p.get("provider_payment_ref");
            VerifiedPayment v;
            try {
                v = strategy.client().verifyPayment(ref);
            } catch (ProviderCallInTransactionException e) {
                throw e;
            } catch (RuntimeException e) {
                unverifiable += unverifiable(ref, e.getClass().getSimpleName());
                continue;
            }
            if (v.status() == ProviderPaymentStatus.PENDING) {
                unverifiable += unverifiable(ref, "provider reports PENDING");
                continue;
            }
            String contradiction = contradiction(p, v);
            if (contradiction == null) continue;
            Map<String, Object> facts = facts(p, "the provider contradicts the ledger: " + contradiction);
            facts.put("providerPaymentRefFetched", v.providerPaymentRef());
            facts.put("providerStatus", v.status().name());
            facts.put("providerAmount", v.amount() == null ? null : v.amount().minor() + " " + v.amount().currency());
            facts.put("providerPaidAt", String.valueOf(v.paidAt()));
            if (flag(p, LEDGER_UNCONFIRMED, false, facts)) flagged++;
        }
        return new int[]{flagged, unverifiable};
    }

    private static String contradiction(Map<String, Object> p, VerifiedPayment v) {
        if (!v.providerPaymentRef().equals(p.get("provider_payment_ref"))) return "the re-fetch names another payment";
        if (v.status() != ProviderPaymentStatus.SUCCEEDED) return "status " + v.status();
        if (v.amount().minor() != ((Number) p.get("amount_minor")).longValue() || !v.amount().currency().name().equals(p.get("currency"))) return "amount or currency";
        if (!v.paidAt().truncatedTo(java.time.temporal.ChronoUnit.MICROS).equals(((Timestamp) p.get("occurred_at")).toInstant())) return "payment time";
        return null;
    }

    // ---- shared ----

    /** One restaurant-scoped exception per charge and code; the restaurant is the ledger row's own. */
    private boolean flag(Map<String, Object> p, String code, boolean funds, Map<String, Object> facts) {
        String businessId = (String) p.get("business_id");
        Boolean created = transaction.execute(s -> {
            locks.configShared();
            locks.restaurant(businessId);
            boolean c = exceptions.create(businessId, code, (String) p.get("id"), (String) p.get("checkout_session_id"), null, null, ((Number) p.get("amount_minor")).longValue(),
                    (String) p.get("currency"), ((Timestamp) p.get("occurred_at")).toInstant(), funds, "OPEN", facts, java.time.Instant.now());
            if (c) audit.success(RECONCILED, null, AdminAuditService.SYSTEM, "PAYMENT", (String) p.get("provider_payment_ref"), "flagged " + code);
            return c;
        });
        if (Boolean.TRUE.equals(created)) log.warn("event=billing.reconcile_mismatch code={} paymentId={}", code, p.get("id"));
        return Boolean.TRUE.equals(created);
    }

    private int unverifiable(String ref, String why) {
        log.warn("event=billing.reconcile_unverifiable ref={} cause={}", ref, why);
        String detail = "unverifiable: " + why;
        if (!jdbc.queryForObject("select exists (select 1 from admin_audit_event where action = ? and target_id = ? and detail = ?)", Boolean.class, RECONCILED, ref, detail)) {
            audit.success(RECONCILED, null, AdminAuditService.SYSTEM, "PAYMENT", ref, detail);
        }
        return 1;
    }

    private void reconciled(String target, String detail) {
        audit.success(RECONCILED, null, AdminAuditService.SYSTEM, "RECONCILIATION", target, detail);
    }

    private static Map<String, Object> facts(Map<String, Object> p, String finding) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("finding", finding);
        f.put("paymentId", p.get("id"));
        f.put("provider", p.get("provider"));
        f.put("providerPaymentRef", p.get("provider_payment_ref"));
        f.put("amount", p.get("amount_minor") + " " + p.get("currency"));
        f.put("occurredAt", String.valueOf(p.get("occurred_at")));
        f.put("verification", p.get("verification"));
        f.put("source", "reconciliation");
        return f;
    }

    private boolean active(String provider) {
        return registry.forProvider(provider).filter(s -> outcomes.billingActive(s.market())).isPresent();
    }

    private List<BillingStrategy> activeStrategies() {
        return java.util.Arrays.stream(Market.values()).map(m -> {
            try {
                return registry.forMarket(m);
            } catch (IllegalStateException none) {
                return null;
            }
        }).filter(s -> s != null && outcomes.billingActive(s.market())).toList();
    }
}
