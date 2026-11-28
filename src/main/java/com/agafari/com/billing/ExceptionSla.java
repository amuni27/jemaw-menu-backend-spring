package com.agafari.com.billing;

import com.agafari.com.security.AdminAuditService;
import com.agafari.com.service.MonetizationLocks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AGF SUB-R4-12 (R-3, I2; PO G1–G4): the deadline of a customer-funds exception. {@code due_at} is the earlier of two
 * business days (in the restaurant's zone, by its market's calendar) and 24 h before the grace ends; with less than 24 h of
 * grace left it is due at once ({@code IMMEDIATE}). Only grace explicitly recorded on the current PROVIDER row counts:
 * AD-7 is open, so a row without recorded grace keeps the business-day deadline and is marked "grace unknown", never
 * treated as zero grace. Nothing new is stored: {@code IMMEDIATE}, overdue and an incomplete calendar are derived (G3), and
 * their transitions are recorded once as audit events and {@code event=billing.*} logs.
 */
@Slf4j
@Component
public class ExceptionSla {

    static final String OVERDUE = "BILLING_EXCEPTION_OVERDUE";
    /** AGF EX-F (register §89 G3-E): the review deadline of an exception without a restaurant. */
    public static final Duration PLATFORM_REVIEW = Duration.ofHours(24);
    static final String RECOMPUTED = "BILLING_EXCEPTION_DUE_RECOMPUTED";
    static final String CALENDAR_INCOMPLETE = "MARKET_CALENDAR_INCOMPLETE";
    static final String OPEN_STATUSES = "('AUTO_HANDLING', 'OPEN', 'IN_REVIEW')"; // ESCALATED meets R-3; RESOLVED is done
    private static final long SWEEP_LOCK = 4_400_000_120L;
    private static final Duration PRIORITY_WINDOW = Duration.ofHours(24);
    private static final ZoneId ADDIS_ABABA = ZoneId.of("Africa/Addis_Ababa");

    /**
     * @param graceUntil   recorded grace end of the current PROVIDER row; null when none applies or it is unknown
     * @param graceUnknown a PROVIDER row without recorded grace: the I2 rule couldn't be applied (AD-7 open)
     */
    public record Due(Instant dueAt, boolean immediate, boolean calendarIncomplete, Instant graceUntil, boolean graceUnknown, String market, String zone) {

        /** The facts kept on the exception when it is created; never overwritten. */
        public Map<String, Object> facts() {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("dueAt", String.valueOf(dueAt));
            f.put("immediate", immediate);
            f.put("calendarIncomplete", calendarIncomplete);
            f.put("graceUntil", graceUntil == null ? null : graceUntil.toString());
            f.put("graceUnknown", graceUnknown);
            f.put("market", market);
            f.put("zone", zone);
            return f;
        }
    }

    private final JdbcTemplate jdbc;
    private final BusinessCalendar calendar;
    private final MonetizationLocks locks;
    private final AdminAuditService audit;
    private final TransactionTemplate transaction;

    public ExceptionSla(JdbcTemplate jdbc, BusinessCalendar calendar, MonetizationLocks locks, AdminAuditService audit, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.locks = locks;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** I2 and G4, pure. */
    public static Due decide(Instant created, BusinessCalendar.SlaDate sla, Instant graceUntil, boolean graceUnknown, String market, String zone) {
        if (graceUntil != null) {
            Instant cutoff = graceUntil.minus(PRIORITY_WINDOW);
            if (!cutoff.isAfter(created)) return new Due(created, true, sla.calendarIncomplete(), graceUntil, false, market, zone);
            if (cutoff.isBefore(sla.due())) return new Due(cutoff, false, sla.calendarIncomplete(), graceUntil, false, market, zone);
        }
        return new Due(sla.due(), false, sla.calendarIncomplete(), graceUntil, graceUnknown, market, zone);
    }

    /** The deadline of an exception of this restaurant created at {@code created}; null if the restaurant has no market. */
    public Due compute(String businessId, Instant created) {
        // AGF EX-F (G3-E): a platform exception has no restaurant, so no market calendar: 24 elapsed hours
        if (businessId == null) return new Due(created.plus(PLATFORM_REVIEW).truncatedTo(ChronoUnit.MICROS), false, false, null, false, null, "UTC");
        Market market = marketOf(businessId);
        if (market == null) return null;
        ZoneId zone = zoneOf(businessId, market);
        Instant graceUntil = null;
        boolean graceUnknown = false;
        List<Map<String, Object>> current = jdbc.queryForList("select source, period_end, grace_days from business_subscription where business_id = ? and status <> 'EXPIRED'", businessId);
        if (current.size() == 1 && "PROVIDER".equals(current.get(0).get("source"))) {
            Object days = current.get(0).get("grace_days"), end = current.get(0).get("period_end");
            if (days != null && end != null && ((Number) days).intValue() >= 0) {
                graceUntil = ((Timestamp) end).toInstant().plus(Duration.ofDays(((Number) days).intValue()));
            } else {
                graceUnknown = true;
            }
        }
        BusinessCalendar.SlaDate sla = calendar.twoBusinessDaysAfter(market, created, zone);
        Due due = decide(created, sla, graceUntil, graceUnknown, market.name(), zone.getId());
        return new Due(due.dueAt().truncatedTo(ChronoUnit.MICROS), due.immediate(), due.calendarIncomplete(), due.graceUntil(), due.graceUnknown(), due.market(), due.zone());
    }

    /**
     * After an audited calendar change, in its transaction: moves the deadline of every open customer-funds exception of
     * the market whose deadline changed, and audits each move. Returns how many moved.
     */
    public int recompute(Market market, String adminId, String actor) {
        int moved = 0;
        for (Map<String, Object> e : jdbc.queryForList("select id, business_id, created_at, due_at from billing_exception where involves_customer_funds and status in "
                + OPEN_STATUSES + " for update")) {
            String businessId = (String) e.get("business_id");
            if (marketOf(businessId) != market) continue;
            Due due = compute(businessId, ((Timestamp) e.get("created_at")).toInstant());
            Instant before = e.get("due_at") == null ? null : ((Timestamp) e.get("due_at")).toInstant();
            if (due == null || due.dueAt().equals(before)) continue;
            jdbc.update("update billing_exception set due_at = ? where id = ?", Timestamp.from(due.dueAt()), e.get("id"));
            audit.success(RECOMPUTED, adminId, actor, "BILLING_EXCEPTION", (String) e.get("id"), before + " -> " + due.dueAt());
            moved++;
        }
        return moved;
    }

    /**
     * The hourly sweep (R-3, R-6): records each newly overdue deadline once, and each market year whose missing calendar
     * affects an open deadline once. A singleton; a concurrent sweep does nothing. Returns how many deadlines were flagged.
     */
    public int sweep(Instant now) {
        Integer flagged = transaction.execute(s -> {
            if (!locks.tryJob(SWEEP_LOCK)) return 0;
            int count = 0;
            // AGF gate 2 (register §98 G2-E): any open deadline is flagged (a failed checkout creation has one without customer
            // funds); the market calendar check stays with customer-funds deadlines, which follow it
            for (Map<String, Object> e : jdbc.queryForList("select id, business_id, created_at, due_at, status, involves_customer_funds from billing_exception where status in "
                    + OPEN_STATUSES + " and due_at is not null")) {
                String id = (String) e.get("id");
                Instant due = ((Timestamp) e.get("due_at")).toInstant();
                if (!due.isAfter(now)) {
                    String detail = "due=" + due;
                    if (!audited(OVERDUE, id, detail)) {
                        audit.success(OVERDUE, null, AdminAuditService.SYSTEM, "BILLING_EXCEPTION", id, detail);
                        log.warn("event=billing.exception_overdue id={} status={} due={}", id, e.get("status"), due);
                        count++;
                    }
                }
                if (!Boolean.TRUE.equals(e.get("involves_customer_funds"))) continue;
                Due current = compute((String) e.get("business_id"), ((Timestamp) e.get("created_at")).toInstant());
                if (current != null && current.calendarIncomplete()) {
                    String target = current.market() + "-" + due.atZone(ZoneOffset.UTC).getYear();
                    if (!audited(CALENDAR_INCOMPLETE, target, null)) {
                        audit.success(CALENDAR_INCOMPLETE, null, AdminAuditService.SYSTEM, "MARKET_CALENDAR", target, "an open deadline uses weekends only");
                        log.warn("event=billing.calendar_incomplete market={} year={}", current.market(), target);
                    }
                }
            }
            escalateUnattributed(now);
            return count;
        });
        return flagged == null ? 0 : flagged;
    }

    /** AGF gate 3 (register §89 G3-E, §101 G3-A, G3-H): an unattributable payment still open after 48 elapsed hours. */
    public static final Duration UNATTRIBUTED_ESCALATION = Duration.ofHours(48);
    static final String UNATTRIBUTED_ESCALATION_REASON = "48 elapsed hours without resolution (register §89 G3-E); status only: no Finance Operations owner is named yet (G3-A)";

    /** AGF D-3 (register §105, §107 D3-A): a restaurant-less payment review (a DEAD payment-event job, or an unverified outcome). */
    static final String PAYMENT_REVIEW_ESCALATION_REASON = "48 elapsed hours without resolution (register §105 D-3); status only: the Finance Operations owner is "
            + "in the operator roster (G3-A, D-2)";

    /**
     * In the sweep's transaction and lock: each still-open {@code UNATTRIBUTED_PAYMENT} (gate 3) and each still-open
     * <b>restaurant-less</b> {@code PAYMENT_OUTCOME_PENDING_REVIEW} (D-3) older than 48 elapsed hours becomes ESCALATED, once,
     * with no target assigned; audited by SYSTEM and alerted at ERROR, naming its money state (verified collected, unknown or
     * unverified), which is never changed. Restaurant-scoped reviews, resolved or already escalated rows are never touched;
     * an admin acting on the old version is refused by the row version.
     */
    private void escalateUnattributed(Instant now) {
        for (Map<String, Object> r : jdbc.queryForList("select id, code, coalesce(facts->>'fundsStatus', case when code = ? then 'COLLECTED_VERIFIED' else 'UNKNOWN' end) as funds "
                        + "from billing_exception where (code = ? or (code = ? and business_id is null)) and status in ('OPEN', 'IN_REVIEW') and escalated_at is null "
                        + "and created_at <= ? order by created_at",
                UnattributedPayments.UNATTRIBUTED, UnattributedPayments.UNATTRIBUTED, UnattributedPayments.PENDING_REVIEW, Timestamp.from(now.minus(UNATTRIBUTED_ESCALATION)))) {
            String id = (String) r.get("id"), code = (String) r.get("code"), funds = (String) r.get("funds");
            String reason = UnattributedPayments.UNATTRIBUTED.equals(code) ? UNATTRIBUTED_ESCALATION_REASON : PAYMENT_REVIEW_ESCALATION_REASON;
            int moved = jdbc.update("update billing_exception set status = 'ESCALATED', escalated_at = ?, escalation_reason = ?, row_version = row_version + 1 "
                    + "where id = ? and status in ('OPEN', 'IN_REVIEW') and escalated_at is null", Timestamp.from(now), reason, id);
            if (moved == 1) {
                audit.success(ExceptionService.ESCALATED, null, AdminAuditService.SYSTEM, "BILLING_EXCEPTION", id,
                        "status-only after 48 elapsed hours; no target (G3-A open); code=" + code + " fundsStatus=" + funds);
                log.error("event=billing.exception_escalated id={} code={} fundsStatus={} after={} escalatedTo=none", id, code, funds, UNATTRIBUTED_ESCALATION);
            }
        }
    }

    private boolean audited(String action, String target, String detail) {
        return detail == null
                ? jdbc.queryForObject("select exists (select 1 from admin_audit_event where action = ? and target_id = ?)", Boolean.class, action, target)
                : jdbc.queryForObject("select exists (select 1 from admin_audit_event where action = ? and target_id = ? and detail = ?)", Boolean.class, action, target, detail);
    }

    /** The billing account's market, else the restaurant's country (V23.1: they are equal). */
    Market marketOf(String businessId) {
        List<String> markets = jdbc.queryForList("select distinct market from billing_account where business_id = ?", String.class, businessId);
        String code = markets.size() == 1 ? markets.get(0) : jdbc.queryForList("select country from business where id = ?", String.class, businessId).stream().findFirst().orElse(null);
        return code == null ? null : Market.fromCountry(code).orElse(null);
    }

    /** G1: the restaurant's validated zone; Ethiopia is always Addis Ababa. */
    private ZoneId zoneOf(String businessId, Market market) {
        if (market == Market.ET) return ADDIS_ABABA;
        String stored = jdbc.queryForList("select time_zone from business where id = ?", String.class, businessId).stream().findFirst().orElse(null);
        try {
            return ZoneId.of(stored);
        } catch (DateTimeException | NullPointerException e) {
            log.warn("event=billing.sla_zone_invalid businessId={}", businessId);
            return ZoneOffset.UTC;
        }
    }
}
