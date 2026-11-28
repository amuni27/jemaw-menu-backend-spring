package com.agafari.com.billing;

import com.agafari.com.dto.request.BillingExceptionActionRequest;
import com.agafari.com.dto.response.BillingExceptionReplayResponse;
import com.agafari.com.dto.response.BillingExceptionView;
import com.agafari.com.exception.BillingExceptionRefusedException;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jobs.PermanentJobException;
import com.agafari.com.jobs.TransientJobException;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.security.LogMasking;
import com.agafari.com.service.MonetizationLocks;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * AGF SUB-R4-11 (DD §8.4, R-3): the one writer of {@code billing_exception}. {@link #create} runs in the caller's
 * transaction, next to the ledger row, and is idempotent per payment and code (or per event and code). Every admin action
 * is its own audited transaction under the restaurant lock, checks the {@code rowVersion} and changes only the review
 * workflow: the database refuses any change to an exception's financial facts.
 *
 * <p>Before PD-9 and AD-9 nothing here activates, refunds or changes a ledger row (BD-17). A replay sends the linked event
 * back through {@link PaymentOutcomeService} with a fresh provider re-fetch and full evidence validation; a payment already
 * in the ledger is never applied twice. An {@code AUTO_HANDLING} exception (an automatic refund in progress) only takes
 * notes until SUB-R4-13 hands it back.
 */
@Service
public class ExceptionService {

    static final String OPENED = "BILLING_EXCEPTION_OPENED";
    static final String CLAIMED = "BILLING_EXCEPTION_CLAIMED";
    static final String NOTE_ADDED = "BILLING_EXCEPTION_NOTE_ADDED";
    static final String ESCALATED = "BILLING_EXCEPTION_ESCALATED";
    static final String RESOLVED = "BILLING_EXCEPTION_RESOLVED";
    static final String EVENT_LINKED = "BILLING_EXCEPTION_EVENT_LINKED";
    /** AGF gate 3 (G3-D): a Stripe refund ({@code re_…}) or payment-record refund ({@code pyr_…}) reference. */
    static final java.util.regex.Pattern STRIPE_REFUND = java.util.regex.Pattern.compile("\\b((?:re|pyr)_[A-Za-z0-9]{8,})\\b");
    static final String REPLAYED = "BILLING_EXCEPTION_REPLAYED";
    static final String REJECTED = "BILLING_EXCEPTION_ACTION_REJECTED";
    static final String HANDED_BACK = "BILLING_EXCEPTION_HANDED_BACK";
    private static final String TARGET = "BILLING_EXCEPTION";
    private static final Set<String> RESOLUTIONS = Set.of("FUNDS_HANDLED_EXTERNALLY", "NO_ACTION_REQUIRED");
    private static final Set<String> REVIEWABLE = Set.of("OPEN", "IN_REVIEW", "ESCALATED");
    private static final Set<String> NOTABLE = Set.of("AUTO_HANDLING", "OPEN", "IN_REVIEW", "ESCALATED");
    private static final int MAX_TEXT = 400;
    private static final int MAX_ESCALATED_TO = 255;

    private static final RowMapper<BillingExceptionView> VIEW = (rs, n) -> new BillingExceptionView(rs.getString("id"), rs.getString("business_id"), rs.getString("code"),
            rs.getString("status"), rs.getString("payment_id"), rs.getString("checkout_id"), rs.getString("provider_event_id"), rs.getString("refund_request_id"),
            (Long) rs.getObject("amount_minor"), rs.getString("currency"), instant(rs.getTimestamp("paid_at")), rs.getBoolean("involves_customer_funds"),
            rs.getString("owner_user_id"), instant(rs.getTimestamp("claimed_at")), instant(rs.getTimestamp("escalated_at")), rs.getString("escalation_reason"),
            rs.getString("escalated_to"), rs.getString("resolution"), rs.getString("resolution_note"), rs.getString("resolved_by"), instant(rs.getTimestamp("resolved_at")),
            instant(rs.getTimestamp("created_at")), rs.getInt("row_version"), instant(rs.getTimestamp("due_at")), null, null, null, rs.getString("source_job_id"));

    private final JdbcTemplate jdbc;
    private final MonetizationLocks locks;
    private final AdminAuditService audit;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final ObjectProvider<PaymentOutcomeService> outcomes;
    private final ExceptionSla sla;
    private final com.agafari.com.service.MonetizationClock clock;

    public ExceptionService(JdbcTemplate jdbc, MonetizationLocks locks, AdminAuditService audit, PlatformTransactionManager transactions, ObjectMapper mapper,
                            ObjectProvider<PaymentOutcomeService> outcomes, ExceptionSla sla, com.agafari.com.service.MonetizationClock clock) {
        this.sla = sla;
        this.clock = clock;
        this.jdbc = jdbc;
        this.locks = locks;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactions);
        this.mapper = mapper;
        this.outcomes = outcomes;
    }

    /**
     * Records an exception in the caller's transaction; false if this payment (or, without a payment, this event) already
     * has one with this code. An existing exception's facts are never overwritten.
     */
    boolean create(String businessId, String code, String paymentId, String checkoutId, String providerEventId, String refundRequestId, Long amountMinor, String currency,
                   Instant paidAt, boolean involvesCustomerFunds, String status, Map<String, Object> facts, Instant now) {
        return create(businessId, code, paymentId, checkoutId, providerEventId, refundRequestId, amountMinor, currency, paidAt, involvesCustomerFunds, status, facts, now, null);
    }

    /**
     * AGF EX-F (register §91): as above, optionally identified by the financial job that failed ({@code sourceJobId}, unique
     * per code). {@code businessId} may be null only for a platform code; the database enforces the rest (no restaurant
     * links, a durable source, and a source job's own restaurant). A platform deadline is 24 elapsed hours (G3-E).
     */
    boolean create(String businessId, String code, String paymentId, String checkoutId, String providerEventId, String refundRequestId, Long amountMinor, String currency,
                   Instant paidAt, boolean involvesCustomerFunds, String status, Map<String, Object> facts, Instant now, String sourceJobId) {
        return create(businessId, code, paymentId, checkoutId, providerEventId, refundRequestId, amountMinor, currency, paidAt, involvesCustomerFunds, status, facts, now,
                sourceJobId, null);
    }

    /**
     * AGF gate 2 (register §98 G2-E): as above; {@code reviewWithin}, when given for an exception without customer funds,
     * sets its deadline to that many elapsed hours (a failed checkout creation: 24 h). It never applies to a customer-funds
     * exception, whose deadline stays the SLA, and it never puts a restaurant under payment review.
     */
    boolean create(String businessId, String code, String paymentId, String checkoutId, String providerEventId, String refundRequestId, Long amountMinor, String currency,
                   Instant paidAt, boolean involvesCustomerFunds, String status, Map<String, Object> facts, Instant now, String sourceJobId, java.time.Duration reviewWithin) {
        return insert(businessId, code, paymentId, checkoutId, providerEventId, refundRequestId, amountMinor, currency, paidAt, involvesCustomerFunds, status, facts, now,
                sourceJobId, reviewWithin, null, null);
    }

    /**
     * AGF gate 3 (register §102 M-1): a restaurant-less exception about one verified payment ({@code paymentProvider},
     * {@code providerPaymentRef}), customer funds, OPEN, linked to the event that delivered it. False when that payment
     * already has one with this code (unique per provider, payment and code, also under concurrency).
     */
    boolean createForPayment(String code, String providerEventId, long amountMinor, String currency, Instant paidAt, Map<String, Object> facts, Instant now,
                             String paymentProvider, String providerPaymentRef) {
        return insert(null, code, null, null, providerEventId, null, amountMinor, currency, paidAt, true, "OPEN", facts, now, null, null, paymentProvider, providerPaymentRef);
    }

    /**
     * AGF gate 3 (G3-G): another event of a payment that already has its exception: the event is linked by an audit entry
     * on that exception (the exception itself is immutable), once per event.
     */
    void linkEventToPayment(String paymentProvider, String providerPaymentRef, String code, String providerEventId, String source) {
        String exceptionId = jdbc.queryForObject("select id from billing_exception where payment_provider = ? and provider_payment_ref = ? and code = ?", String.class,
                paymentProvider, providerPaymentRef, code);
        boolean own = providerEventId.equals(jdbc.queryForObject("select provider_event_id from billing_exception where id = ?", String.class, exceptionId));
        String detail = "providerEvent=" + providerEventId + " source=" + source;
        boolean linked = jdbc.queryForObject("select exists (select 1 from admin_audit_event where action = ? and target_id = ? and detail = ?)", Boolean.class,
                EVENT_LINKED, exceptionId, detail);
        if (own || linked) return; // a replay of an event already traced
        audit.success(EVENT_LINKED, null, AdminAuditService.SYSTEM, TARGET, exceptionId, detail);
        org.slf4j.LoggerFactory.getLogger(ExceptionService.class).warn("event=billing.exception_event_linked id={} code={} providerEvent={} source={}", exceptionId, code,
                providerEventId, source);
    }

    private boolean insert(String businessId, String code, String paymentId, String checkoutId, String providerEventId, String refundRequestId, Long amountMinor,
                           String currency, Instant paidAt, boolean involvesCustomerFunds, String status, Map<String, Object> facts, Instant now, String sourceJobId,
                           java.time.Duration reviewWithin, String paymentProvider, String providerPaymentRef) {
        String id = UUID.randomUUID().toString();
        Instant created = now.truncatedTo(ChronoUnit.MICROS);
        // AGF SUB-R4-12 (R-3, I2): a customer-funds exception gets its deadline now; its basis is kept with the facts
        ExceptionSla.Due due = involvesCustomerFunds ? sla.compute(businessId, created) : null;
        Map<String, Object> recorded = new java.util.LinkedHashMap<>(facts);
        if (due != null) recorded.put("sla", due.facts());
        if (businessId == null) recorded.put("slaBasis", "24 elapsed hours (no restaurant, register §89 G3-E)");
        if (!involvesCustomerFunds && reviewWithin != null) {
            due = new ExceptionSla.Due(created.plus(reviewWithin).truncatedTo(ChronoUnit.MICROS), false, false, null, false, null, "UTC");
            recorded.put("slaBasis", reviewWithin.toHours() + " elapsed hours (no customer funds; register §98 G2-E)");
        }
        int inserted = jdbc.update("insert into billing_exception (id, business_id, code, payment_id, checkout_id, provider_event_id, refund_request_id, amount_minor, currency, "
                        + "paid_at, involves_customer_funds, facts, status, created_at, due_at, source_job_id, payment_provider, provider_payment_ref) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?) on conflict do nothing",
                id, businessId, code, paymentId, checkoutId, providerEventId, refundRequestId, amountMinor, currency, timestamp(paidAt), involvesCustomerFunds,
                mapper.writeValueAsString(recorded), status, timestamp(created), due == null ? null : timestamp(due.dueAt()), sourceJobId, paymentProvider, providerPaymentRef);
        if (inserted == 1) {
            String deadline = due == null ? "" : " due=" + due.dueAt() + " immediate=" + due.immediate() + " calendarIncomplete=" + due.calendarIncomplete()
                    + " graceUnknown=" + due.graceUnknown();
            audit.success(OPENED, null, AdminAuditService.SYSTEM, TARGET, id, "code=" + code + " status=" + status + deadline);
            // AGF EX-F (G3-B): every new exception is a log event for alerting; one without a restaurant is an error
            org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExceptionService.class);
            String restaurant = businessId == null ? "none" : businessId;
            Object dueAt = due == null ? "none" : due.dueAt();
            if (businessId == null) log.error("event=billing.exception_opened id={} code={} restaurant={} due={}", id, code, restaurant, dueAt);
            else log.warn("event=billing.exception_opened id={} code={} restaurant={} due={}", id, code, restaurant, dueAt);
            if (due != null && due.immediate()) org.slf4j.LoggerFactory.getLogger(ExceptionService.class).warn("event=billing.exception_immediate id={} due={}", id, due.dueAt());
            if (due != null && due.calendarIncomplete()) {
                org.slf4j.LoggerFactory.getLogger(ExceptionService.class).warn("event=billing.calendar_incomplete id={} market={}", id, due.market());
            }
        }
        return inserted == 1;
    }

    /**
     * AGF SUB-R4-13 (PO B4, Q1): the approved refund flow hands the charge's AUTO_HANDLING exception back for manual
     * resolution. Code and facts don't change; the reason is in the audit trail. In the caller's transaction, under the
     * restaurant lock; repeating it changes nothing.
     */
    /**
     * AGF SUB-R5-02 (PO U2/U4): a provider subscription created by this payment couldn't be cancelled safely, so the payment's
     * own outcome exception goes to an operator: {@code AUTO_HANDLING → OPEN}; an exception already with an operator gets an
     * audit note. Never a second exception for the payment; code and facts never change. In the caller's transaction, under
     * the restaurant lock; repeating it changes nothing new. Returns the exception's id, or null if the payment has none.
     */
    String handBackPayment(String paymentId, String reason, String detail) {
        List<Map<String, Object>> rows = jdbc.queryForList("select id, status from billing_exception where payment_id = ? and code <> 'LEDGER_FACTS_MISMATCH' order by created_at for update",
                paymentId);
        if (rows.isEmpty()) return null;
        String id = (String) rows.get(0).get("id");
        String text = clip("reason=" + reason + " payment=" + paymentId + " " + detail);
        if (jdbc.update("update billing_exception set status = 'OPEN', row_version = row_version + 1 where id = ? and status = 'AUTO_HANDLING'", id) == 1) {
            audit.success(HANDED_BACK, null, AdminAuditService.SYSTEM, TARGET, id, text + " AUTO_HANDLING -> OPEN");
        } else {
            audit.success(PROVIDER_SUBSCRIPTION_HANDED_BACK, null, AdminAuditService.SYSTEM, TARGET, id, text);
        }
        org.slf4j.LoggerFactory.getLogger(ExceptionService.class).warn("event=billing.provider_subscription_handed_back exceptionId={} paymentId={} reason={}", id, paymentId, reason);
        return id;
    }

    static final String PROVIDER_SUBSCRIPTION_HANDED_BACK = "PROVIDER_SUBSCRIPTION_HANDED_BACK";

    void handBack(String refundRequestId, String reason, String refundStatus) {
        List<String> ids = jdbc.queryForList("select id from billing_exception where refund_request_id = ? and status = 'AUTO_HANDLING' for update", String.class, refundRequestId);
        for (String id : ids) {
            if (jdbc.update("update billing_exception set status = 'OPEN', row_version = row_version + 1 where id = ? and status = 'AUTO_HANDLING'", id) == 1) {
                audit.success(HANDED_BACK, null, AdminAuditService.SYSTEM, TARGET, id, "reason=" + reason + " refund=" + refundRequestId + " refundStatus=" + refundStatus
                        + " AUTO_HANDLING -> OPEN");
                org.slf4j.LoggerFactory.getLogger(ExceptionService.class).warn("event=billing.refund_handed_back exceptionId={} refundId={} reason={}", id, refundRequestId, reason);
            }
        }
    }

    public BillingExceptionView get(String id) {
        if (!onPostgres()) throw new NotFoundException("Exception not found"); // AGF EX-F: no billing tables outside PostgreSQL
        return derive(load(id).orElseThrow(() -> new NotFoundException("Exception not found")));
    }

    /** AGF SUB-R4-12 (G3): conditions derived from stored data, never stored themselves. */
    private BillingExceptionView derive(BillingExceptionView v) {
        if (!v.involvesCustomerFunds() || v.dueAt() == null) return v;
        boolean open = Set.of("AUTO_HANDLING", "OPEN", "IN_REVIEW").contains(v.status());
        ExceptionSla.Due current = sla.compute(v.businessId(), v.createdAt());
        return new BillingExceptionView(v.id(), v.businessId(), v.code(), v.status(), v.paymentId(), v.checkoutId(), v.providerEventId(), v.refundRequestId(), v.amountMinor(),
                v.currency(), v.paidAt(), v.involvesCustomerFunds(), v.ownerUserId(), v.claimedAt(), v.escalatedAt(), v.escalationReason(), v.escalatedTo(), v.resolution(),
                v.resolutionNote(), v.resolvedBy(), v.resolvedAt(), v.createdAt(), v.rowVersion(), v.dueAt(), v.dueAt().equals(v.createdAt()),
                open && !v.dueAt().isAfter(clock.now()), current != null && current.calendarIncomplete(), v.sourceJobId());
    }

    // ---- AGF EX-F (register §91): the Super Admin list, badge counts and history ----

    private static final String OPEN_STATE_SQL = "('AUTO_HANDLING', 'OPEN', 'IN_REVIEW')";
    private volatile Boolean postgres;

    /** The billing tables exist only on PostgreSQL (the in-memory demo builds its schema from entities): elsewhere there are none. */
    private boolean onPostgres() {
        Boolean known = postgres;
        if (known == null) {
            known = Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c ->
                    c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgresql")));
            postgres = known;
        }
        return known;
    }
    private static final int MAX_PAGE = 100;

    /** {@code state}: UNRESOLVED (default), RESOLVED or ALL. Unresolved first by deadline (none last), then newest. */
    public com.agafari.com.dto.response.BillingExceptionPage list(String state, int page, int size) {
        String where = switch (state == null ? "UNRESOLVED" : state) {
            case "UNRESOLVED" -> "e.status <> 'RESOLVED'";
            case "RESOLVED" -> "e.status = 'RESOLVED'";
            case "ALL" -> "true";
            default -> throw new com.agafari.com.exception.BadRequestException("state must be UNRESOLVED, RESOLVED or ALL");
        };
        if (page < 0 || size < 1 || size > MAX_PAGE) throw new com.agafari.com.exception.BadRequestException("page must be 0 or more and size between 1 and " + MAX_PAGE);
        if (!onPostgres()) return new com.agafari.com.dto.response.BillingExceptionPage(List.of(), page, size, 0);
        Timestamp now = timestamp(clock.now());
        long total = jdbc.queryForObject("select count(*) from billing_exception e where " + where, Long.class);
        List<com.agafari.com.dto.response.BillingExceptionPage.Item> items = jdbc.query("""
                select e.*, b.name as business_name, u.email as owner_email,
                       (e.status in %s and e.due_at is not null and e.due_at <= ?) as is_overdue
                from billing_exception e left join business b on b.id = e.business_id left join users u on u.id = e.owner_user_id
                where %s order by (e.status = 'RESOLVED'), e.due_at asc nulls last, e.created_at desc, e.id limit ? offset ?
                """.formatted(OPEN_STATE_SQL, where), (rs, n) -> new com.agafari.com.dto.response.BillingExceptionPage.Item(rs.getString("id"), rs.getString("code"),
                rs.getString("status"), rs.getString("business_id"), rs.getString("business_name"), (Long) rs.getObject("amount_minor"), rs.getString("currency"),
                rs.getBoolean("involves_customer_funds"), instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("due_at")), rs.getBoolean("is_overdue"),
                rs.getString("owner_email") == null ? null : LogMasking.maskEmail(rs.getString("owner_email")), rs.getString("escalated_to"),
                rs.getString("provider_event_id"), rs.getString("source_job_id")), now, size, (long) page * size);
        return new com.agafari.com.dto.response.BillingExceptionPage(items, page, size, total);
    }

    /** PO F-2: each unresolved record counted once; open, escalated and automatic handling split it; overdue is separate. */
    public com.agafari.com.dto.response.BillingExceptionSummary summary() {
        if (!onPostgres()) return new com.agafari.com.dto.response.BillingExceptionSummary(0, 0, 0, 0, 0);
        return jdbc.queryForObject("""
                select count(*) filter (where status <> 'RESOLVED') as unresolved,
                       count(*) filter (where status in ('OPEN', 'IN_REVIEW')) as open,
                       count(*) filter (where status = 'ESCALATED') as escalated,
                       count(*) filter (where status = 'AUTO_HANDLING') as auto_handling,
                       count(*) filter (where status in %s and due_at is not null and due_at <= ?) as overdue
                from billing_exception
                """.formatted(OPEN_STATE_SQL), (rs, n) -> new com.agafari.com.dto.response.BillingExceptionSummary(rs.getLong("unresolved"), rs.getLong("open"),
                rs.getLong("escalated"), rs.getLong("auto_handling"), rs.getLong("overdue")), timestamp(clock.now()));
    }

    /** The exception's own audit entries, newest first (who did what, with notes). */
    public List<com.agafari.com.dto.response.AdminAuditPage.Item> history(String id) {
        if (!onPostgres() || load(id).isEmpty()) throw new NotFoundException("Exception not found");
        return jdbc.query("select * from admin_audit_event where target_type = ? and target_id = ? order by occurred_at desc, id desc",
                (rs, n) -> new com.agafari.com.dto.response.AdminAuditPage.Item(rs.getString("id"), instant(rs.getTimestamp("occurred_at")), rs.getString("actor_label"),
                        rs.getString("action"), rs.getString("target_type"), rs.getString("target_id"), rs.getString("outcome"), rs.getString("detail")), TARGET, id);
    }

    public BillingExceptionView claim(String id, BillingExceptionActionRequest request, String adminId, String adminEmail) {
        return change(id, request, adminId, adminEmail, "claim", Set.of("OPEN"), CLAIMED,
                "status = 'IN_REVIEW', owner_user_id = ?, claimed_at = now()", List.of(adminId), "OPEN -> IN_REVIEW");
    }

    public BillingExceptionView addNote(String id, BillingExceptionActionRequest request, String adminId, String adminEmail) {
        String note = text(request.note(), "note");
        return change(id, request, adminId, adminEmail, "note", NOTABLE, NOTE_ADDED, null, List.of(), "note: " + note);
    }

    public BillingExceptionView escalate(String id, BillingExceptionActionRequest request, String adminId, String adminEmail) {
        String reason = text(request.reason(), "reason");
        String to = request.escalatedTo() == null || request.escalatedTo().isBlank() ? null : request.escalatedTo().strip();
        if (to != null && to.length() > MAX_ESCALATED_TO) throw new FieldValidationException(Map.of("escalatedTo", "At most " + MAX_ESCALATED_TO + " characters"));
        List<Object> args = new ArrayList<>();
        args.add(reason);
        args.add(to);
        return change(id, request, adminId, adminEmail, "escalate", Set.of("OPEN", "IN_REVIEW"), ESCALATED,
                "status = 'ESCALATED', escalated_at = now(), escalation_reason = ?, escalated_to = ?", args, "-> ESCALATED: " + reason);
    }

    public BillingExceptionView resolve(String id, BillingExceptionActionRequest request, String adminId, String adminEmail) {
        if (request.resolution() == null || !RESOLUTIONS.contains(request.resolution())) {
            throw new FieldValidationException(Map.of("resolution", "One of FUNDS_HANDLED_EXTERNALLY or NO_ACTION_REQUIRED"));
        }
        String note = text(request.note(), "note");
        String detail = "-> RESOLVED " + request.resolution();
        // AGF gate 3 (register §101, G3-D): an unattributable payment's money can only be returned in Stripe, outside Agafari;
        // resolving it as handled externally needs that refund's reference in the audited note
        if ("FUNDS_HANDLED_EXTERNALLY".equals(request.resolution()) && UnattributedPayments.UNATTRIBUTED.equals(codeOf(id))) {
            java.util.regex.Matcher refund = STRIPE_REFUND.matcher(note);
            if (!refund.find()) throw new FieldValidationException(Map.of("note", "Give the Stripe refund reference (re_…) of the money returned in Stripe"));
            detail += " stripeRefund=" + refund.group(1);
        }
        return change(id, request, adminId, adminEmail, "resolve", REVIEWABLE, RESOLVED,
                "status = 'RESOLVED', resolution = ?, resolution_note = ?, resolved_by = ?, resolved_at = now()", List.of(request.resolution(), note, adminId),
                detail);
    }

    private String codeOf(String id) {
        if (!onPostgres()) return null;
        return jdbc.queryForList("select code from billing_exception where id = ?", String.class, id).stream().findFirst().orElse(null);
    }

    /**
     * Re-runs the exception's event through {@link PaymentOutcomeService}: a fresh provider re-fetch, the matrix and the
     * lifecycle's evidence validation. The exception itself is left for the admin to close.
     */
    public BillingExceptionReplayResponse replay(String id, BillingExceptionActionRequest request, String adminId, String adminEmail) {
        String actor = LogMasking.maskEmail(adminEmail);
        BillingExceptionView view = change(id, request, adminId, adminEmail, "replay", REVIEWABLE, null, null, List.of(), null);
        if (view.providerEventId() == null) throw rejected(adminId, actor, id, "not_replayable", HttpStatus.CONFLICT, BillingExceptionRefusedException.NOT_REPLAYABLE,
                "This exception has no provider event to replay.");
        String result;
        try {
            result = outcomes.getObject().replay(view.providerEventId()).name();
        } catch (PermanentJobException e) {
            throw rejected(adminId, actor, id, "billing_inactive", HttpStatus.CONFLICT, BillingExceptionRefusedException.NOT_REPLAYABLE,
                    "The event can't be replayed now: billing isn't active for its provider.");
        } catch (TransientJobException e) {
            result = "UNRESOLVED";
        }
        audit.success(REPLAYED, adminId, actor, TARGET, id, "event=" + view.providerEventId() + " result=" + result);
        return new BillingExceptionReplayResponse(get(id), result);
    }

    // ---- one audited, version-checked change ----

    private BillingExceptionView change(String id, BillingExceptionActionRequest request, String adminId, String adminEmail, String action, Set<String> allowed,
                                        String auditAction, String set, List<Object> setArgs, String detail) {
        String actor = LogMasking.maskEmail(adminEmail);
        if (request == null || request.rowVersion() == null) throw new FieldValidationException(Map.of("rowVersion", "Reload and try again"));
        List<Map<String, Object>> found = jdbc.queryForList("select business_id from billing_exception where id = ?", id);
        if (found.isEmpty()) throw new NotFoundException("Exception not found");
        String businessId = (String) found.get(0).get("business_id"); // AGF EX-F: null for a platform exception
        return transaction.execute(s -> {
            if (businessId != null) locks.restaurant(businessId); // a platform exception touches no restaurant: its row lock (below) is enough
            Map<String, Object> row = jdbc.queryForMap("select status, row_version from billing_exception where id = ? for update", id);
            if (((Number) row.get("row_version")).intValue() != request.rowVersion()) {
                throw rejected(adminId, actor, id, action + ":version_conflict", HttpStatus.CONFLICT, BillingExceptionRefusedException.VERSION_CONFLICT,
                        "The exception was changed by someone else. Reload to see the latest version.");
            }
            String status = (String) row.get("status");
            if (!allowed.contains(status)) {
                throw rejected(adminId, actor, id, action + ":status_" + status, HttpStatus.CONFLICT, BillingExceptionRefusedException.NOT_ALLOWED,
                        "This action isn't possible while the exception is " + status + ".");
            }
            if (auditAction == null) return get(id); // a replay changes nothing here
            List<Object> args = new ArrayList<>(setArgs);
            args.add(id);
            args.add(request.rowVersion());
            int updated = jdbc.update("update billing_exception set " + (set == null ? "" : set + ", ") + "row_version = row_version + 1 where id = ? and row_version = ?", args.toArray());
            if (updated != 1) throw new IllegalStateException("The exception changed under its lock"); // never: checked above under FOR UPDATE
            audit.success(auditAction, adminId, actor, TARGET, id, clip(detail));
            return get(id);
        });
    }

    private BillingExceptionRefusedException rejected(String adminId, String actor, String id, String reason, HttpStatus status, String code, String message) {
        audit.failure(REJECTED, adminId, actor, TARGET, id, reason);
        return new BillingExceptionRefusedException(status, code, message);
    }

    private java.util.Optional<BillingExceptionView> load(String id) {
        return jdbc.query("select * from billing_exception where id = ?", VIEW, id).stream().findFirst();
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) throw new FieldValidationException(Map.of(field, "Required"));
        String v = value.strip();
        if (v.length() > MAX_TEXT) throw new FieldValidationException(Map.of(field, "At most " + MAX_TEXT + " characters"));
        return v;
    }

    private static String clip(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static Timestamp timestamp(Instant i) {
        return i == null ? null : Timestamp.from(i.truncatedTo(ChronoUnit.MICROS));
    }
}
