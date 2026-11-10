package com.agafari.com.billing;

import com.agafari.com.billing.port.BillingStrategy;
import com.agafari.com.billing.port.PaymentProviderClient;
import com.agafari.com.billing.port.ProviderCapabilities;
import com.agafari.com.billing.port.ProviderRefund;
import com.agafari.com.billing.port.ProviderRejectedException;
import com.agafari.com.billing.port.RefundSubmission;
import com.agafari.com.jobs.JobFailurePolicy;
import com.agafari.com.jobs.JobQueue;
import com.agafari.com.jobs.PermanentJobException;
import com.agafari.com.jobs.ProviderCallGuard;
import com.agafari.com.jobs.ProviderCallInTransactionException;
import com.agafari.com.jobs.TransientJobException;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.MonetizationLocks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * AGF SUB-R4-13 (BD-22, BD-25, R-1, DD §10.2): asks for a refund and submits it without ever refunding twice or losing one.
 *
 * <p>Approved scope (PO B1–B4): a refund ends here at {@code SUBMITTED}. That isn't success: {@code SUCCEEDED}, the
 * {@code REFUND} ledger row and resolving the exception as {@code REFUNDED_AUTOMATICALLY} need verified provider evidence,
 * which the separate settlement story provides. Refund webhooks aren't processed here.
 *
 * <ul>
 *   <li><b>Request</b> (B3): only a full refund of a verified charge of the same restaurant; under the locks, no second
 *   non-failed request for the charge and never more than the charge in total. FIN-05 and GA-7 stay release gates.</li>
 *   <li><b>Submit</b>: {@code SUBMITTING}, the key {@code refund:<id>} and the first submission time are committed before the
 *   provider is called, outside any transaction.</li>
 *   <li><b>Retry</b>: only an {@code UNKNOWN} outcome, only with the same key, only inside the provider's documented
 *   idempotency window, and only with idempotent refunds confirmed. Otherwise a presence-only lookup: found is recorded;
 *   nothing found proves nothing and is never followed by a submission.</li>
 *   <li><b>Hand-back</b> (B4): a definite rejection, an outcome that can't be settled safely, or a missing capability moves the
 *   charge's existing {@code AUTO_HANDLING} exception to {@code OPEN}, with the reason in the audit trail.</li>
 * </ul>
 */
@Slf4j
@Service
public class RefundService {

    public static final String JOB_TYPE = "refund.execute";
    static final String REFUND_REJECTED = "REFUND_REJECTED";
    static final String REFUND_OUTCOME_UNCERTAIN = "REFUND_OUTCOME_UNCERTAIN";
    private static final String REQUESTED_ACTION = "BILLING_REFUND_REQUESTED";
    private static final String SUBMITTED_ACTION = "BILLING_REFUND_SUBMITTED";
    private static final String TARGET = "REFUND_REQUEST";

    public enum Outcome { SUBMITTED, FAILED_DEFINITE, UNKNOWN_RETRY, HANDED_BACK, NOTHING_TO_DO }

    private record Refund(String id, String status, String idempotencyKey, Instant submittedAt, long amountMinor, String paymentId, String businessId, String provider,
                          String providerPaymentRef, String currency) {
    }

    private final JdbcTemplate jdbc;
    private final BillingStrategyRegistry registry;
    private final PaymentOutcomeService outcomes;
    private final ExceptionService exceptions;
    private final MonetizationLocks locks;
    private final MonetizationClock clock;
    private final JobQueue jobs;
    private final AdminAuditService audit;
    private final TransactionTemplate transaction;

    public RefundService(JdbcTemplate jdbc, BillingStrategyRegistry registry, @org.springframework.context.annotation.Lazy PaymentOutcomeService outcomes,
                         ExceptionService exceptions, MonetizationLocks locks, MonetizationClock clock, JobQueue jobs, AdminAuditService audit,
                         PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.outcomes = outcomes;
        this.exceptions = exceptions;
        this.locks = locks;
        this.clock = clock;
        this.jobs = jobs;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactions);
    }

    // ---- request ----

    /**
     * In the caller's transaction, under the configuration and restaurant locks: the id of the refund request for this
     * charge and key, creating it and its {@code refund.execute} job if needed.
     *
     * @throws IllegalStateException if the charge isn't a verified charge of this restaurant, or another non-failed request
     *                               already covers it (B3)
     */
    String request(String businessId, String paymentId, String reason, String requestKey) {
        locks.assertHeld(businessId);
        Map<String, Object> pay = jdbc.queryForList("select business_id, amount_minor, kind, status, verified_at from payment where id = ? for share", paymentId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No such payment"));
        if (!businessId.equals(pay.get("business_id"))) throw new IllegalStateException("The payment belongs to another restaurant");
        if (!"CHARGE".equals(pay.get("kind")) || !"SUCCEEDED".equals(pay.get("status")) || pay.get("verified_at") == null) {
            throw new IllegalStateException("Only a verified charge can be refunded");
        }
        long amount = ((Number) pay.get("amount_minor")).longValue();
        List<String> same = jdbc.queryForList("select id from refund_request where payment_id = ? and request_key = ?", String.class, paymentId, requestKey);
        if (!same.isEmpty()) return same.get(0);
        long exposure = jdbc.queryForObject("select coalesce(sum(amount_minor), 0) from refund_request where payment_id = ? and status not in ('FAILED', 'FAILED_DEFINITE')",
                Long.class, paymentId);
        if (exposure > 0) throw new IllegalStateException("A refund request already covers this charge");
        if (exposure + amount > amount) throw new IllegalStateException("Refunds can't exceed the charge"); // full refunds only: amount is the whole charge
        String id = UUID.randomUUID().toString();
        jdbc.update("insert into refund_request (id, payment_id, amount_minor, reason, request_key, status) values (?, ?, ?, ?, ?, 'REQUESTED')", id, paymentId, amount, reason, requestKey);
        audit.success(REQUESTED_ACTION, null, AdminAuditService.SYSTEM, TARGET, id, "reason=" + reason);
        jobs.enqueue(JOB_TYPE, id, new RefundExecuteHandler.Payload(id));
        return id;
    }

    // ---- execute ----

    public Outcome execute(String refundRequestId) {
        ProviderCallGuard.assertNoTransaction();
        Refund r = load(refundRequestId).orElseThrow(() -> new PermanentJobException("No such refund request " + refundRequestId, null));
        if (!List.of("REQUESTED", "SUBMITTING", "UNKNOWN").contains(r.status())) return Outcome.NOTHING_TO_DO;
        // B4: once handed to an operator, the automatic flow does nothing more with this refund
        if (!jdbc.queryForObject("select exists (select 1 from billing_exception where refund_request_id = ? and status = 'AUTO_HANDLING')", Boolean.class, r.id())) {
            return Outcome.NOTHING_TO_DO;
        }
        BillingStrategy strategy = registry.forProvider(r.provider()).filter(s -> outcomes.billingActive(s.market())).orElse(null);
        if (strategy == null) throw new PermanentJobException("Billing isn't active for provider " + r.provider() + "; refund " + r.id() + " stays " + r.status(), null);
        PaymentProviderClient client = strategy.client();
        ProviderCapabilities capabilities = client.capabilities();

        if ("REQUESTED".equals(r.status())) {
            if (!capabilities.idempotentRefund()) return handBack(r, PaymentOutcome.REFUND_REQUIRED_MANUAL); // BD-25, NC-26
            Instant now = clock.now().truncatedTo(ChronoUnit.MICROS);
            String key = "refund:" + r.id();
            boolean mine = Boolean.TRUE.equals(transaction.execute(s -> {
                locks.restaurant(r.businessId());
                return jdbc.update("update refund_request set status = 'SUBMITTING', idempotency_key = ?, submitted_at = ? where id = ? and status = 'REQUESTED'",
                        key, Timestamp.from(now), r.id()) == 1;
            }));
            if (!mine) return Outcome.NOTHING_TO_DO; // another worker owns this submission
            return submit(client, load(r.id()).orElseThrow());
        }

        if ("SUBMITTING".equals(r.status())) {
            // a worker stopped between committing SUBMITTING and recording the answer: the outcome is unknown
            if (!toUnknown(r, "worker stopped while SUBMITTING")) return Outcome.NOTHING_TO_DO;
        }
        return resolveUnknown(client, capabilities, load(r.id()).orElseThrow());
    }

    /** UNKNOWN: a same-key retry only when demonstrably safe; otherwise presence-only lookup, then hand-back. */
    private Outcome resolveUnknown(PaymentProviderClient client, ProviderCapabilities capabilities, Refund r) {
        if (!"UNKNOWN".equals(r.status())) return Outcome.NOTHING_TO_DO;
        if (retrySafe(capabilities, r.submittedAt(), clock.now())) {
            boolean mine = Boolean.TRUE.equals(transaction.execute(s -> {
                locks.restaurant(r.businessId());
                return jdbc.update("update refund_request set status = 'SUBMITTING' where id = ? and status = 'UNKNOWN'", r.id()) == 1;
            }));
            if (!mine) return Outcome.NOTHING_TO_DO;
            return submit(client, load(r.id()).orElseThrow());
        }
        if (capabilities.refundLookup()) {
            Optional<ProviderRefund> found;
            try {
                found = client.findRefund(r.providerPaymentRef(), r.id());
            } catch (ProviderCallInTransactionException e) {
                throw e;
            } catch (RuntimeException e) {
                if (lastRun(r.id())) return handBack(r, REFUND_OUTCOME_UNCERTAIN);
                throw new TransientJobException("The refund lookup failed", e);
            }
            if (found.isPresent()) return recordSubmitted(r, found.get().providerRefundRef(), "UNKNOWN", "found by lookup");
        }
        return handBack(r, REFUND_OUTCOME_UNCERTAIN); // nothing found proves nothing: never resubmitted
    }

    /** The provider call; {@code r} is SUBMITTING with its key and first submission time committed. */
    private Outcome submit(PaymentProviderClient client, Refund r) {
        RefundSubmission submission;
        try {
            submission = client.submitRefund(r.providerPaymentRef(), new Money(r.amountMinor(), Currency.valueOf(r.currency())), r.idempotencyKey(),
                    Map.of("refund_request_id", r.id()));
        } catch (ProviderRejectedException e) {
            transaction.executeWithoutResult(s -> {
                locks.restaurant(r.businessId());
                jdbc.update("update refund_request set status = 'FAILED_DEFINITE' where id = ? and status = 'SUBMITTING'", r.id());
            });
            handBack(r, REFUND_REJECTED);
            return Outcome.FAILED_DEFINITE; // never retried: a new attempt would need an approved admin action (AD-9)
        } catch (ProviderCallInTransactionException e) {
            throw e;
        } catch (RuntimeException e) {
            // timeout, connection reset, 5xx or anything unexpected: the provider may have acted
            toUnknown(r, e.getClass().getSimpleName());
            if (lastRun(r.id())) return handBack(r, REFUND_OUTCOME_UNCERTAIN);
            throw new TransientJobException("The refund outcome is unknown", e);
        }
        return recordSubmitted(r, submission.providerRefundRef(), "SUBMITTING", "submitted");
    }

    private Outcome recordSubmitted(Refund r, String providerRefundRef, String from, String how) {
        boolean recorded = Boolean.TRUE.equals(transaction.execute(s -> {
            locks.restaurant(r.businessId());
            int updated = jdbc.update("update refund_request set status = 'SUBMITTED', provider_refund_ref = ? where id = ? and status = ?", providerRefundRef, r.id(), from);
            if (updated == 1) audit.success(SUBMITTED_ACTION, null, AdminAuditService.SYSTEM, TARGET, r.id(), how + " ref=" + providerRefundRef);
            return updated == 1;
        }));
        return recorded ? Outcome.SUBMITTED : Outcome.NOTHING_TO_DO;
    }

    private boolean toUnknown(Refund r, String why) {
        return Boolean.TRUE.equals(transaction.execute(s -> {
            locks.restaurant(r.businessId());
            boolean moved = jdbc.update("update refund_request set status = 'UNKNOWN' where id = ? and status = 'SUBMITTING'", r.id()) == 1;
            if (moved) log.warn("event=billing.refund_unknown refundId={} cause={}", r.id(), why);
            return moved;
        }));
    }

    /** B4: the charge's AUTO_HANDLING exception goes to OPEN for manual resolution. Repeating it changes nothing. */
    private Outcome handBack(Refund r, String reason) {
        transaction.executeWithoutResult(s -> {
            locks.restaurant(r.businessId());
            exceptions.handBack(r.id(), reason, r.status());
        });
        return Outcome.HANDED_BACK;
    }

    /** BD-22: confirmed idempotent refunds, a confirmed window, and still inside it, counted from the first submission. */
    static boolean retrySafe(ProviderCapabilities capabilities, Instant firstSubmittedAt, Instant now) {
        return capabilities.idempotentRefund() && !capabilities.idempotencyWindow().isZero() && firstSubmittedAt != null
                && now.isBefore(firstSubmittedAt.plus(capabilities.idempotencyWindow()));
    }

    /** Whether the runner will end this job after a transient failure now, by its own rules: hand back rather than go DEAD. */
    private boolean lastRun(String refundId) {
        List<Map<String, Object>> jobRows = jdbc.queryForList("select claims, first_attempt_at, now() as db_now from job where type = ? and key = ?", JOB_TYPE, refundId);
        if (jobRows.isEmpty() || jobRows.get(0).get("first_attempt_at") == null) return false;
        Map<String, Object> job = jobRows.get(0);
        return JobFailurePolicy.decide(JobFailurePolicy.Kind.TRANSIENT, ((Number) job.get("claims")).intValue(), ((Timestamp) job.get("first_attempt_at")).toInstant(),
                ((Timestamp) job.get("db_now")).toInstant(), JobFailurePolicy.transientCap(JOB_TYPE, null)).dead();
    }

    private Optional<Refund> load(String id) {
        return jdbc.query("select r.id, r.status, r.idempotency_key, r.submitted_at, r.amount_minor, r.payment_id, p.business_id, p.provider, p.provider_payment_ref, p.currency "
                        + "from refund_request r join payment p on p.id = r.payment_id where r.id = ?",
                (rs, n) -> new Refund(rs.getString("id"), rs.getString("status"), rs.getString("idempotency_key"),
                        rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant(), rs.getLong("amount_minor"), rs.getString("payment_id"),
                        rs.getString("business_id"), rs.getString("provider"), rs.getString("provider_payment_ref"), rs.getString("currency")), id).stream().findFirst();
    }
}
