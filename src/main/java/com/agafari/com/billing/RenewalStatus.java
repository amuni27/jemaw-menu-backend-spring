package com.agafari.com.billing;

import com.agafari.com.dto.response.OwnerPlanView;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.subscription.EffectiveStateEvaluator;
import com.agafari.com.subscription.EffectiveView;
import com.agafari.com.subscription.LifecycleStatus;
import com.agafari.com.subscription.SubscriptionSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AGF SUB-R5-03-B (addendum §13; assessment story 2): what an owner sees about a PROVIDER subscription's renewal. Read-only,
 * for the restaurant's own rows only; grace and past-due come from the same effective-state rules as entitlements
 * ({@link EffectiveStateEvaluator}). Nothing here is evidence or changes any row.
 */
@Component
public class RenewalStatus {

    public static final String PAST_DUE = "PAST_DUE";
    public static final String ENDED_PAYMENT_FAILED = "ENDED_PAYMENT_FAILED";
    public static final String REFUND_PENDING = "REFUND_PENDING";
    private static final List<String> REFUND_CAUSES = List.of(RenewalOutcome.PAID_AFTER_EXPIRY, RenewalOutcome.DUPLICATE_PERIOD);

    private final JdbcTemplate jdbc;
    private final EffectiveStateEvaluator evaluator;
    private volatile Boolean postgres;

    public RenewalStatus(JdbcTemplate jdbc, EffectiveStateEvaluator evaluator) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
    }

    /** {@code period_end} + the recorded grace, for a PROVIDER row that recorded one; otherwise null (omitted). */
    public Instant graceUntil(BusinessSubscription s) {
        if (s == null || !provider(s) || s.getGraceDays() == null || s.getPeriodEnd() == null) return null;
        return s.getPeriodEnd().plus(java.time.Duration.ofDays(s.getGraceDays()));
    }

    /** When a PROVIDER row became past due, while it is; otherwise null (omitted). */
    public Instant pastDueSince(BusinessSubscription s) {
        if (s == null || !provider(s) || LifecycleStatus.of(s.getStatus()) != LifecycleStatus.PAST_DUE) return null;
        return s.getPastDueSince();
    }

    /** The renewal summary, if the owner needs to see one. {@code current} is the row the plan view treats as current (or null). */
    public Optional<OwnerPlanView.Renewal> of(String businessId, BusinessSubscription current, String timeZone, Instant now) {
        OwnerPlanView.RefundPending refund = refundPending(businessId);
        if (current != null && provider(current)) {
            EffectiveView view = evaluator.view(current, false, now);
            boolean pastDue = LifecycleStatus.of(current.getStatus()) == LifecycleStatus.PAST_DUE || "IN_GRACE".equals(view.reasonCode());
            if (pastDue) return Optional.of(new OwnerPlanView.Renewal(PAST_DUE, current.getPeriodEnd(), graceUntil(current), null, refund, timeZone));
        }
        if (current == null) {
            List<Map<String, Object>> latest = jdbc.queryForList("select source, status, ended_reason, ended_at, period_end from business_subscription where business_id = ? "
                    + "order by created_at desc, id desc limit 1", businessId);
            if (!latest.isEmpty() && "PROVIDER".equals(latest.get(0).get("source")) && "EXPIRED".equals(latest.get(0).get("status"))
                    && "PAYMENT_FAILED".equals(latest.get(0).get("ended_reason"))) {
                Map<String, Object> r = latest.get(0);
                return Optional.of(new OwnerPlanView.Renewal(ENDED_PAYMENT_FAILED, instant(r.get("period_end")), null, instant(r.get("ended_at")), refund, timeZone));
            }
        }
        return refund == null ? Optional.empty() : Optional.of(new OwnerPlanView.Renewal(REFUND_PENDING, null, null, null, refund, timeZone));
    }

    /** An unresolved renewal charge Agafari didn't apply and will refund (automatically when safe, or by an operator). */
    private OwnerPlanView.RefundPending refundPending(String businessId) {
        if (!onPostgres()) return null; // billing tables exist only on PostgreSQL
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select amount_minor, currency, case when code = 'REFUND_REQUIRED_MANUAL' then facts->>'cause' else code end as reason from billing_exception
                where business_id = ? and status <> 'RESOLVED'
                  and (code in (?, ?) or (code = 'REFUND_REQUIRED_MANUAL' and facts->>'cause' in (?, ?)))
                order by created_at, id limit 1""", businessId, REFUND_CAUSES.get(0), REFUND_CAUSES.get(1), REFUND_CAUSES.get(0), REFUND_CAUSES.get(1));
        if (rows.isEmpty()) return null;
        Map<String, Object> r = rows.get(0);
        return new OwnerPlanView.RefundPending(r.get("amount_minor") == null ? null : ((Number) r.get("amount_minor")).longValue(), (String) r.get("currency"), (String) r.get("reason"));
    }

    private static boolean provider(BusinessSubscription s) {
        return SubscriptionSource.of(s.getSource()) == SubscriptionSource.PROVIDER;
    }

    private boolean onPostgres() {
        Boolean known = postgres;
        if (known == null) {
            known = Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c ->
                    c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql")));
            postgres = known;
        }
        return known;
    }

    private static Instant instant(Object t) {
        return t == null ? null : ((Timestamp) t).toInstant();
    }
}
