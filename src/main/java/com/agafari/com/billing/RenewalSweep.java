package com.agafari.com.billing;

import com.agafari.com.billing.port.BillingStrategy;
import com.agafari.com.billing.port.ProviderInvoiceState;
import com.agafari.com.billing.port.ProviderSubscriptionSnapshot;
import com.agafari.com.billing.webhook.InboxService;
import com.agafari.com.jobs.JobHandler;
import com.agafari.com.jobs.JobQueue;
import com.agafari.com.jobs.ProviderCallGuard;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.service.MonetizationClock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * AGF SUB-R5-03 (addendum §4, P-R1): the renewal sweep, so a lost {@code invoice.paid} webhook can't expire a paying customer.
 * For every current PROVIDER row whose {@code period_end} has passed without a renewal, inside its grace, the provider's
 * subscription and its latest invoice are re-fetched at {@code period_end + 1 h}, then daily, and once at
 * {@code grace_end - 6 h}. A paid renewal invoice not yet in the ledger becomes an internal reconciliation event, so it takes
 * the <b>same</b> outcome path as a webhook ({@code RECONCILIATION_API_FETCH}); the sweep itself writes nothing financial.
 * Only for markets whose billing is active (never while {@link ProviderActivationReadiness} is closed).
 */
@Slf4j
@Component
public class RenewalSweep {

    public static final String PLAN_TYPE = "billing.renewal-sweep-plan";
    public static final String CHECK_TYPE = "billing.renewal-sweep";
    static final String DELETED_SEEN = "PROVIDER_SUBSCRIPTION_ENDED_SEEN_BY_SWEEP";
    private static final Duration FIRST = Duration.ofHours(1);
    private static final Duration FINAL_BEFORE_GRACE_END = Duration.ofHours(6);

    public record PlanPayload(String bucket) {
    }

    /** @param periodEnd the row's {@code period_end} when planned; a renewed row (moved period end) is skipped */
    public record CheckPayload(String subscriptionId, String periodEnd) {
    }

    private final JdbcTemplate jdbc;
    private final JobQueue jobs;
    private final BillingStrategyRegistry registry;
    private final PaymentOutcomeService outcomes;
    private final InboxService inbox;
    private final AdminAuditService audit;
    private final TransactionTemplate transaction;

    public RenewalSweep(JdbcTemplate jdbc, JobQueue jobs, BillingStrategyRegistry registry, PaymentOutcomeService outcomes, InboxService inbox, AdminAuditService audit,
                        PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.registry = registry;
        this.outcomes = outcomes;
        this.inbox = inbox;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactions);
    }

    /**
     * The slot due at {@code now}: {@code d0} from {@code period_end + 1 h}, {@code dN} each following day, {@code final} from
     * {@code grace_end - 6 h}; null before the first slot or from the grace end on. One check per slot (the job key).
     */
    static String slot(Instant periodEnd, Instant graceEnd, Instant now) {
        Instant first = periodEnd.plus(FIRST);
        if (now.isBefore(first) || !now.isBefore(graceEnd)) return null;
        if (!now.isBefore(graceEnd.minus(FINAL_BEFORE_GRACE_END))) return "final";
        return "d" + Duration.between(first, now).toDays();
    }

    /** Plans the due checks, one job per row and slot, in one transaction. */
    public int plan(Instant now) {
        Integer planned = transaction.execute(s -> {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select s.id, s.provider, s.period_end, s.grace_days from business_subscription s
                    where s.provider_subscription_ref is not null and s.status in ('ACTIVE', 'PAST_DUE')
                      and s.period_end is not null and s.grace_days is not null and s.period_end <= ?""", Timestamp.from(now.minus(FIRST)));
            int count = 0;
            for (Map<String, Object> r : rows) {
                if (!active((String) r.get("provider"))) continue;
                Instant periodEnd = ((Timestamp) r.get("period_end")).toInstant();
                String slot = slot(periodEnd, periodEnd.plus(Duration.ofDays(((Number) r.get("grace_days")).intValue())), now);
                if (slot == null) continue;
                jobs.enqueue(CHECK_TYPE, r.get("id") + ":" + periodEnd.getEpochSecond() + ":" + slot, new CheckPayload((String) r.get("id"), periodEnd.toString()));
                count++;
            }
            return count;
        });
        return planned == null ? 0 : planned;
    }

    /** One row's check, outside any transaction: re-fetch, and hand a paid renewal invoice to the outcome path. */
    public void check(CheckPayload p) {
        ProviderCallGuard.assertNoTransaction();
        List<Map<String, Object>> rows = jdbc.queryForList("select provider, provider_subscription_ref, period_end, status from business_subscription where id = ?", p.subscriptionId());
        if (rows.isEmpty()) return;
        Map<String, Object> r = rows.get(0);
        if ("EXPIRED".equals(r.get("status")) || !((Timestamp) r.get("period_end")).toInstant().equals(Instant.parse(p.periodEnd()))) return; // renewed or ended meanwhile
        String provider = (String) r.get("provider"), subscriptionRef = (String) r.get("provider_subscription_ref");
        BillingStrategy strategy = registry.forProvider(provider).filter(st -> outcomes.billingActive(st.market())).orElse(null);
        if (strategy == null) return;
        ProviderSubscriptionSnapshot sub = strategy.client().subscriptionState(subscriptionRef);
        if (sub.livemode()) return;
        if (sub.ended()) {
            audit.success(DELETED_SEEN, null, AdminAuditService.SYSTEM, "PROVIDER_SUBSCRIPTION", subscriptionRef, "subscription=" + p.subscriptionId() + " ended at " + sub.endedAt());
        }
        if (sub.latestInvoiceRef() == null) return;
        ProviderInvoiceState inv = strategy.client().invoiceState(sub.latestInvoiceRef());
        if (inv.livemode() || !inv.renewal() || inv.status() != ProviderInvoiceState.Status.PAID || !subscriptionRef.equals(inv.subscriptionRef())) return;
        if (jdbc.queryForObject("select count(*) from payment where provider = ? and provider_payment_ref = ? and kind = 'CHARGE'", Integer.class, provider, inv.invoiceRef()) > 0) return;
        String eventId = inbox.storeInternal(provider, inv.invoiceRef());
        log.info("event=billing.renewal_sweep_found subscriptionId={} invoice={} providerEventId={}", p.subscriptionId(), inv.invoiceRef(), eventId);
    }

    private boolean active(String provider) {
        return registry.forProvider(provider).filter(st -> outcomes.billingActive(st.market())).isPresent();
    }

    /** The hourly planner (enqueued by {@link ReconciliationCadence}). */
    @Component
    public static class Planner implements JobHandler<PlanPayload> {

        private final RenewalSweep sweep;
        private final MonetizationClock clock;

        public Planner(RenewalSweep sweep, MonetizationClock clock) {
            this.sweep = sweep;
            this.clock = clock;
        }

        @Override
        public String type() {
            return PLAN_TYPE;
        }

        @Override
        public Class<PlanPayload> payloadType() {
            return PlanPayload.class;
        }

        @Override
        public void handle(PlanPayload payload) {
            sweep.plan(clock.now());
        }
    }

    /** One re-fetch; the provider comes from the row, so the per-provider pause applies (R4-16-D1). */
    @Component
    public static class Checker implements JobHandler<CheckPayload> {

        private final RenewalSweep sweep;

        public Checker(RenewalSweep sweep) {
            this.sweep = sweep;
        }

        @Override
        public String type() {
            return CHECK_TYPE;
        }

        @Override
        public Class<CheckPayload> payloadType() {
            return CheckPayload.class;
        }

        @Override
        public String providerSql() {
            return "(select s.provider from business_subscription s where s.id = j.payload->>'subscriptionId')";
        }

        @Override
        public void handle(CheckPayload payload) {
            sweep.check(payload);
        }
    }
}
