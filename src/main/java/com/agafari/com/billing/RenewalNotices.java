package com.agafari.com.billing;

import com.agafari.com.jobs.JobHandler;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.notifications.NotificationKind;
import com.agafari.com.notifications.NotificationOutbox;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.subscription.EffectiveStateEvaluator;
import com.agafari.com.subscription.LifecycleStatus;
import com.agafari.com.subscription.SubscriptionLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AGF SUB-R5-03-D (D-R5; N2–N4, register §69): the owner's renewal notices, in-app through {@link NotificationOutbox} (email
 * waits for D5). Each notice is one outbox row per restaurant, kind and key, so nothing is ever sent twice.
 *
 * <ul>
 *   <li><b>Reminders</b> ({@code RENEWAL_PAYMENT_REQUEST}): days 0, 3 and 6 of grace at 09:00 in the restaurant's time zone
 *   (UTC when none is recorded), counted from {@code period_end}; day 0 never before the period end itself. Only the latest
 *   one due is sent, only while the row is past due (stored, or in grace by time per the entitlement rules), and the outbox
 *   pauses them while a payment is under review (I1). Planned hourly.</li>
 *   <li><b>Plan ended</b> ({@code EXPIRY_NOTICE}): a PROVIDER row ended {@code PAYMENT_FAILED} in the last 7 days. Planned hourly.</li>
 *   <li><b>Payment received</b> and <b>plan ended, refund pending</b>: written by {@link RenewalOutcomes} in the outcome's own
 *   transaction.</li>
 * </ul>
 * Only for markets whose billing is active. Template data holds no personal data: plan, dates, amounts, the time zone.
 */
@Component
public class RenewalNotices {

    public static final String PLAN_TYPE = "billing.renewal-notices";
    private static final int[] REMINDER_DAYS = {0, 3, 6};
    private static final LocalTime REMINDER_TIME = LocalTime.of(9, 0);
    private static final Duration ENDED_LOOKBACK = Duration.ofDays(7);
    private static final int BATCH = 500;

    public record PlanPayload(String bucket) {
    }

    private final JdbcTemplate jdbc;
    private final NotificationOutbox outbox;
    private final BusinessSubscriptionRepository subscriptions;
    private final EffectiveStateEvaluator evaluator;
    private final com.agafari.com.subscription.HeldFunding billingGate; // the market billing gate, through its narrow interface
    private final MonetizationLocks locks;
    private final TransactionTemplate transaction;

    public RenewalNotices(JdbcTemplate jdbc, NotificationOutbox outbox, BusinessSubscriptionRepository subscriptions, EffectiveStateEvaluator evaluator,
                          com.agafari.com.subscription.HeldFunding billingGate, MonetizationLocks locks, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.subscriptions = subscriptions;
        this.evaluator = evaluator;
        this.billingGate = billingGate;
        this.locks = locks;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** N4: day {@code day} of grace at 09:00 in {@code zone} (UTC if null); day 0 never before the period end itself. */
    static Instant reminderAt(Instant periodEnd, ZoneId zone, int day) {
        ZoneId z = zone == null ? ZoneOffset.UTC : zone;
        Instant at = periodEnd.atZone(z).toLocalDate().plusDays(day).atTime(REMINDER_TIME).atZone(z).toInstant();
        return day == 0 && at.isBefore(periodEnd) ? periodEnd : at;
    }

    /** Plans the due reminders and plan-ended notices; returns how many notices were added. */
    public int plan(Instant now) {
        int added = 0;
        for (Map<String, Object> r : jdbc.queryForList("""
                select s.id, s.business_id, s.provider, s.period_end, s.grace_days, b.time_zone from business_subscription s join business b on b.id = s.business_id
                where s.provider is not null and s.status in ('ACTIVE', 'PAST_DUE') and s.grace_days is not null and s.period_end is not null and s.period_end <= ?
                order by s.period_end, s.id limit ?""", Timestamp.from(now), BATCH)) {
            if (!billingGate.billingActive((String) r.get("business_id"))) continue;
            if (reminder(r, now)) added++;
        }
        for (Map<String, Object> r : jdbc.queryForList("""
                select s.id, s.business_id, s.provider, s.plan_code, s.ended_at, b.time_zone from business_subscription s join business b on b.id = s.business_id
                where s.provider is not null and s.status = 'EXPIRED' and s.ended_reason = 'PAYMENT_FAILED' and s.ended_at >= ?
                order by s.ended_at, s.id limit ?""", Timestamp.from(now.minus(ENDED_LOOKBACK)), BATCH)) {
            if (!billingGate.billingActive((String) r.get("business_id"))) continue;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("planCode", r.get("plan_code"));
            data.put("endedAt", instant(r.get("ended_at")).toString());
            data.put("timeZone", zoneName(r.get("time_zone")));
            String businessId = (String) r.get("business_id");
            if (Boolean.TRUE.equals(transaction.execute(s -> outbox.add(businessId, NotificationKind.EXPIRY_NOTICE, "ended:" + r.get("id"), data)))) added++;
        }
        return added;
    }

    private boolean reminder(Map<String, Object> r, Instant now) {
        String businessId = (String) r.get("business_id"), id = (String) r.get("id");
        Instant periodEnd = instant(r.get("period_end"));
        ZoneId zone = zone(r.get("time_zone"));
        int due = -1;
        for (int day : REMINDER_DAYS) if (!now.isBefore(reminderAt(periodEnd, zone, day))) due = day;
        if (due < 0) return false;
        int day = due;
        return Boolean.TRUE.equals(transaction.execute(s -> {
            locks.restaurant(businessId);
            BusinessSubscription row = subscriptions.findById(id).orElse(null);
            // still the same unpaid period, and still past due (stored, or in grace by time)
            if (row == null || !row.isCurrent() || !periodEnd.equals(row.getPeriodEnd())) return false;
            boolean pastDue = LifecycleStatus.of(row.getStatus()) == LifecycleStatus.PAST_DUE || "IN_GRACE".equals(evaluator.view(row, false, now).reasonCode());
            Instant graceUntil = SubscriptionLifecycle.graceEnd(row);
            if (!pastDue || graceUntil == null || !now.isBefore(graceUntil)) return false;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("planCode", row.getPlanCode());
            data.put("periodEnd", periodEnd.toString());
            data.put("graceUntil", graceUntil.toString());
            data.put("day", day);
            data.put("timeZone", zoneName(r.get("time_zone")));
            return outbox.add(businessId, NotificationKind.RENEWAL_PAYMENT_REQUEST, "renewal:" + id + ":" + periodEnd.getEpochSecond() + ":d" + day, data);
        }));
    }

    /** §6: a failed renewal was paid within grace. In the outcome's transaction. */
    public void paymentReceived(String businessId, String paymentId, String planCode, Instant newPeriodEnd, Money amount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planCode", planCode);
        data.put("periodEnd", newPeriodEnd.toString());
        data.put("amountMinor", amount.minor());
        data.put("currency", amount.currency().name());
        data.put("timeZone", zoneName(timeZoneOf(businessId)));
        outbox.add(businessId, NotificationKind.PAYMENT_RECEIVED, "payment:" + paymentId, data);
    }

    /** §7: the plan had ended when the renewal was paid; it will be refunded. In the outcome's transaction. */
    public void planEndedRefund(String businessId, String paymentId, String planCode, Instant endedAt, Money amount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planCode", planCode);
        data.put("endedAt", endedAt == null ? null : endedAt.toString());
        data.put("amountMinor", amount.minor());
        data.put("currency", amount.currency().name());
        data.put("timeZone", zoneName(timeZoneOf(businessId)));
        outbox.add(businessId, NotificationKind.PLAN_ENDED_REFUND, "payment:" + paymentId, data);
    }

    private Object timeZoneOf(String businessId) {
        List<String> zones = jdbc.queryForList("select time_zone from business where id = ?", String.class, businessId);
        return zones.isEmpty() ? null : zones.get(0);
    }

    private static ZoneId zone(Object name) {
        if (name == null || name.toString().isBlank()) return null;
        try {
            return ZoneId.of(name.toString());
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static String zoneName(Object name) {
        ZoneId z = zone(name);
        return (z == null ? ZoneOffset.UTC : z).getId();
    }

    private static Instant instant(Object t) {
        return ((Timestamp) t).toInstant();
    }

    /** The hourly planner (enqueued by {@link ReconciliationCadence}). */
    @Component
    public static class Planner implements JobHandler<PlanPayload> {

        private final RenewalNotices notices;
        private final MonetizationClock clock;

        public Planner(RenewalNotices notices, MonetizationClock clock) {
            this.notices = notices;
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
            notices.plan(clock.now());
        }
    }
}
