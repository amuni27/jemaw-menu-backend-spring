package com.agafari.com.billing;

import com.agafari.com.billing.port.BillingStrategy;
import com.agafari.com.billing.port.PaymentProviderClient;
import com.agafari.com.billing.port.ProviderRejectedException;
import com.agafari.com.billing.port.ProviderSubscriptionState;
import com.agafari.com.billing.port.ProviderTimeoutException;
import com.agafari.com.billing.port.SubscriptionCancelResult;
import com.agafari.com.jobs.JobFailurePolicy;
import com.agafari.com.jobs.JobHandler;
import com.agafari.com.jobs.PermanentJobException;
import com.agafari.com.jobs.ProviderCallGuard;
import com.agafari.com.jobs.TransientJobException;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.subscription.HeldFunding;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AGF SUB-R5-02 (D4; PO U2–U4): the {@code checkout.provider-subscription-cancel} job. A provider subscription created by an
 * Agafari checkout whose payment reached a terminal non-activation (matrix rows 2–5) is cancelled immediately, so the
 * provider never renews it. Every check (C1–C6) uses the provider's authoritative re-fetch, never a webhook body; any check
 * that fails, or an outcome that stays uncertain, hands the payment's existing exception back to an operator. Refunds stay
 * with the matrix and {@link RefundService}: cancelling creates no refund, credit or final invoice.
 *
 * <p>The {@code checkout.} prefix makes this a financial job (72 h cap, never pruned). Its provider comes from the payment
 * row, so the per-provider pause applies (R4-16-D1).
 */
@Slf4j
@Component
public class ProviderSubscriptionCancelHandler implements JobHandler<ProviderSubscriptionCancelHandler.Payload> {

    public static final String TYPE = "checkout.provider-subscription-cancel";
    static final String CANCELLED = "PROVIDER_SUBSCRIPTION_CANCELLED";
    static final String ALREADY_CANCELLED = "PROVIDER_SUBSCRIPTION_ALREADY_CANCELLED";
    private static final String TARGET = "PROVIDER_SUBSCRIPTION";
    private static final Set<Integer> TERMINAL_ROWS = Set.of(2, 3, 4, 5);

    public record Payload(String paymentId) {
    }

    private record Paid(String id, String businessId, String provider, String ref, String checkoutId, boolean verifiedCharge, Integer row) {
    }

    private final JdbcTemplate jdbc;
    private final BillingStrategyRegistry registry;
    private final HeldFunding billingGate; // the market billing gate, through its narrow interface
    private final ExceptionService exceptions;
    private final MonetizationLocks locks;
    private final AdminAuditService audit;
    private final TransactionTemplate transaction;

    public ProviderSubscriptionCancelHandler(JdbcTemplate jdbc, BillingStrategyRegistry registry, HeldFunding billingGate, ExceptionService exceptions,
                                             MonetizationLocks locks, AdminAuditService audit, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.billingGate = billingGate;
        this.exceptions = exceptions;
        this.locks = locks;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public String providerSql() {
        return "(select p.provider from payment p where p.id = j.payload->>'paymentId')";
    }

    @Override
    public void handle(Payload payload) {
        ProviderCallGuard.assertNoTransaction();
        Paid paid = load(payload.paymentId());
        if (paid == null) throw new PermanentJobException("No payment " + payload.paymentId() + " with an outcome exception", null);

        // C1: a verified charge of this provider, decided as a terminal non-activation (rows 2–5)
        if (!paid.verifiedCharge() || paid.row() == null || !TERMINAL_ROWS.contains(paid.row())) {
            handBack(paid, "C1_NOT_A_TERMINAL_VERIFIED_CHARGE", "row=" + paid.row());
            return;
        }
        BillingStrategy strategy = billingGate.billingActive(paid.businessId()) ? registry.forProvider(paid.provider()).orElse(null) : null;
        if (strategy == null) throw new PermanentJobException("Billing isn't active for provider " + paid.provider() + "; payment " + paid.id() + " is left as it is", null);
        PaymentProviderClient client = strategy.client();
        if (!client.capabilities().cancelSubscription()) {
            handBack(paid, "CAPABILITY_NOT_CONFIRMED", "");
            return;
        }

        ProviderSubscriptionState state;
        try {
            state = client.subscriptionOfPayment(paid.ref()); // authoritative re-fetch, every attempt
        } catch (ProviderTimeoutException e) {
            if (lastRun(paid.id())) {
                handBack(paid, "CANCELLATION_OUTCOME_UNCERTAIN", "the provider could not be re-fetched");
                return;
            }
            throw new TransientJobException("The provider timed out re-fetching a subscription", e);
        } catch (ProviderRejectedException e) {
            handBack(paid, "PROVIDER_REFUSED_LOOKUP", e.getClass().getSimpleName());
            return;
        }

        // C3: test-mode objects only while the R5 gates are open
        if (state.livemode()) {
            handBack(paid, "C3_LIVE_MODE_OBJECT", "");
            return;
        }
        if (state.status() == ProviderSubscriptionState.Status.NOT_FOUND || state.subscriptionRef() == null) {
            handBack(paid, "PROVIDER_SUBSCRIPTION_NOT_FOUND", "");
            return;
        }
        // C2: the provider's own checkout record names this payment's checkout and restaurant
        if (!paid.checkoutId().equals(state.agafariCheckoutId()) || !paid.businessId().equals(state.agafariBusinessId())) {
            handBack(paid, "C2_OWNERSHIP_NOT_PROVEN", "subscription=" + state.subscriptionRef());
            return;
        }
        // C4: never cancel a subscription that backs an Agafari entitlement (checked under the restaurant lock)
        boolean activated = Boolean.TRUE.equals(transaction.execute(s -> {
            locks.restaurant(paid.businessId());
            return jdbc.queryForObject("select count(*) from business_subscription where provider_subscription_ref = ?", Integer.class, state.subscriptionRef()) > 0;
        }));
        if (activated) {
            handBack(paid, "C4_SUBSCRIPTION_IS_ACTIVATED", "subscription=" + state.subscriptionRef());
            return;
        }
        // C6: already ended → nothing to do; otherwise only a cancellable status
        if (state.ended()) {
            record(ALREADY_CANCELLED, paid, state.subscriptionRef(), "status=" + state.status());
            return;
        }
        if (!state.cancellable()) {
            handBack(paid, "C6_STATUS_NOT_CANCELLABLE", "status=" + state.status());
            return;
        }
        // C5: cancelling stops collection of every finalized invoice of the customer, so never while another is open
        if (state.otherOpenInvoices()) {
            handBack(paid, "C5_OTHER_OPEN_INVOICES", "subscription=" + state.subscriptionRef());
            return;
        }

        SubscriptionCancelResult result;
        try {
            result = client.cancelSubscription(state.subscriptionRef());
        } catch (ProviderTimeoutException e) {
            // uncertain: the next attempt re-fetches first (cancelling is idempotent by definition at the provider)
            if (lastRun(paid.id())) {
                handBack(paid, "CANCELLATION_OUTCOME_UNCERTAIN", "subscription=" + state.subscriptionRef());
                return;
            }
            throw new TransientJobException("The provider timed out cancelling a subscription", e);
        } catch (ProviderRejectedException e) {
            handBack(paid, "PROVIDER_REFUSED_CANCELLATION", "subscription=" + state.subscriptionRef());
            return;
        }
        record(result == SubscriptionCancelResult.CANCELLED ? CANCELLED : ALREADY_CANCELLED, paid, state.subscriptionRef(), "payment=" + paid.id());
    }

    private Paid load(String paymentId) {
        List<Paid> rows = jdbc.query("""
                select p.id, p.business_id, p.provider, p.provider_payment_ref, p.checkout_session_id,
                       (p.kind = 'CHARGE' and p.status = 'SUCCEEDED' and p.verified_at is not null) as verified_charge, e.facts->>'row' as decided_row
                from payment p join billing_exception e on e.payment_id = p.id and e.code <> 'LEDGER_FACTS_MISMATCH'
                where p.id = ? order by e.created_at limit 1""",
                (rs, n) -> new Paid(rs.getString("id"), rs.getString("business_id"), rs.getString("provider"), rs.getString("provider_payment_ref"),
                        rs.getString("checkout_session_id"), rs.getBoolean("verified_charge"), rs.getString("decided_row") == null ? null : Integer.valueOf(rs.getString("decided_row"))),
                paymentId);
        return rows.isEmpty() || rows.get(0).checkoutId() == null ? null : rows.get(0);
    }

    private void handBack(Paid paid, String reason, String detail) {
        transaction.executeWithoutResult(s -> {
            locks.restaurant(paid.businessId());
            exceptions.handBackPayment(paid.id(), reason, detail);
        });
        log.warn("event=billing.provider_subscription_not_cancelled paymentId={} reason={}", paid.id(), reason);
    }

    private void record(String action, Paid paid, String subscriptionRef, String detail) {
        transaction.executeWithoutResult(s -> audit.success(action, null, AdminAuditService.SYSTEM, TARGET, subscriptionRef, detail));
        log.info("event=billing.provider_subscription_{} paymentId={} subscription={}", action.equals(CANCELLED) ? "cancelled" : "already_cancelled", paid.id(), subscriptionRef);
    }

    /** As the refund job: the last permitted run hands an unresolved outcome back rather than leaving it dead. */
    private boolean lastRun(String paymentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select claims, first_attempt_at, now() as db_now from job where type = ? and key = ?", TYPE, "payment:" + paymentId);
        if (rows.isEmpty() || rows.get(0).get("first_attempt_at") == null) return false;
        Map<String, Object> job = rows.get(0);
        return JobFailurePolicy.decide(JobFailurePolicy.Kind.TRANSIENT, ((Number) job.get("claims")).intValue(), ((Timestamp) job.get("first_attempt_at")).toInstant(),
                ((Timestamp) job.get("db_now")).toInstant(), JobFailurePolicy.transientCap(TYPE, null)).dead();
    }
}
