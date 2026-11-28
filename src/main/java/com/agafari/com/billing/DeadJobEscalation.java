package com.agafari.com.billing;

import com.agafari.com.billing.webhook.InboxService;
import com.agafari.com.jobs.DeadJobListener;
import com.agafari.com.service.MonetizationClock;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AGF gate 2, R7b (register §98): a financial job in scope that becomes DEAD gets exactly one
 * {@code PAYMENT_OUTCOME_PENDING_REVIEW} exception, written by {@link ExceptionService} <b>in the transaction of the DEAD
 * update</b> ({@link DeadJobListener}). Attribution comes only from the job ({@code billing_job_restaurant}, the V23.6 rule
 * the database also enforces): a payment-event job has no restaurant; a checkout, refund or cancellation job has its own.
 * Nothing is retried, replayed, refunded or activated here; recovery uses the existing actions (G2-I).
 * <ul>
 *   <li>G2-C: {@code inbox.process}, {@code checkout.create}, {@code refund.*} and {@code checkout.provider-subscription-cancel}.
 *   {@code checkout.expire} is excluded: the hourly reconciliation closes its checkout, and a payment made on it is judged by
 *   the outcome matrix.</li>
 *   <li>G2-E/G2-F: payment events, refunds and cancellations involve customer funds (the SUB-R4-11 SLA, or 24 elapsed hours
 *   without a restaurant; a restaurant's new paid checkouts are blocked while it is open). A failed checkout creation is due
 *   in 24 elapsed hours and blocks nothing.</li>
 * </ul>
 */
@Component
public class DeadJobEscalation implements DeadJobListener {

    public static final String CODE = "PAYMENT_OUTCOME_PENDING_REVIEW";
    /** G2-E: the review deadline of a failed checkout creation, which involves no customer funds. */
    public static final Duration CHECKOUT_CREATE_REVIEW = Duration.ofHours(24);
    static final Set<String> ESCALATED = Set.of(InboxService.JOB_TYPE, CheckoutCreateHandler.TYPE, ProviderSubscriptionCancelHandler.TYPE);
    static final String REFUND_PREFIX = "refund.";

    private final JdbcTemplate jdbc;
    private final ExceptionService exceptions;
    private final MonetizationClock clock;
    private final ObjectMapper mapper;
    private volatile Boolean postgres;

    public DeadJobEscalation(JdbcTemplate jdbc, ExceptionService exceptions, MonetizationClock clock, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.exceptions = exceptions;
        this.clock = clock;
        this.mapper = mapper;
    }

    public static boolean escalated(String type) {
        return type != null && (ESCALATED.contains(type) || type.startsWith(REFUND_PREFIX));
    }

    @Override
    public void onDead(DeadJob job) {
        if (!escalated(job.type()) || !onPostgres()) return;
        String businessId = jdbc.queryForObject("select billing_job_restaurant(?)", String.class, job.id());
        JsonNode payload = mapper.readTree(job.payload());
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("jobId", job.id());
        facts.put("jobType", job.type());
        facts.put("jobKey", job.key());
        facts.put("claims", job.claims());
        facts.put("firstAttemptAt", job.firstAttemptAt() == null ? null : job.firstAttemptAt().toString());
        facts.put("lastErrorKind", job.lastErrorKind());
        facts.put("lastError", job.lastError());
        facts.put("deadPath", job.path().name());

        String eventId = null, checkoutId = null, paymentId = null, refundId = null;
        Long amount = null;
        String currency = null;
        boolean funds = true;
        Duration review = null;
        if (InboxService.JOB_TYPE.equals(job.type())) {
            eventId = text(payload, "providerEventId");
            List<Map<String, Object>> event = jdbc.queryForList("select provider, event_id, type, status, attempts from provider_event where id = ?", eventId);
            if (!event.isEmpty()) {
                facts.put("provider", event.get(0).get("provider"));
                facts.put("eventId", event.get(0).get("event_id"));
                facts.put("eventType", event.get(0).get("type"));
                facts.put("eventStatus", event.get(0).get("status"));
                facts.put("eventAttempts", event.get(0).get("attempts"));
            }
        } else if (CheckoutCreateHandler.TYPE.equals(job.type())) {
            checkoutId = text(payload, "checkoutId");
            funds = false;
            review = CHECKOUT_CREATE_REVIEW;
        } else if (ProviderSubscriptionCancelHandler.TYPE.equals(job.type())) {
            paymentId = text(payload, "paymentId");
            Map<String, Object> pay = paymentOf(paymentId);
            if (pay != null) {
                amount = ((Number) pay.get("amount_minor")).longValue();
                currency = (String) pay.get("currency");
            }
        } else {
            refundId = text(payload, "refundRequestId");
            List<Map<String, Object>> refund = jdbc.queryForList("select r.payment_id, r.amount_minor, p.currency from refund_request r join payment p on p.id = r.payment_id where r.id = ?",
                    refundId);
            if (!refund.isEmpty()) {
                facts.put("paymentId", refund.get(0).get("payment_id"));
                amount = ((Number) refund.get(0).get("amount_minor")).longValue();
                currency = (String) refund.get(0).get("currency");
            }
        }
        exceptions.create(businessId, CODE, paymentId, checkoutId, eventId, refundId, amount, currency, null, funds, "OPEN", facts, clock.now(), job.id(), review);
    }

    private Map<String, Object> paymentOf(String paymentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("select amount_minor, currency from payment where id = ?", paymentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String text(JsonNode payload, String field) {
        String value = payload.path(field).asString("");
        return value.isBlank() ? null : value;
    }

    /** Billing tables exist only on PostgreSQL; on the H2 demo a dead job is only logged, as before. */
    private boolean onPostgres() {
        Boolean known = postgres;
        if (known == null) {
            known = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c -> c.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql")));
            postgres = known;
        }
        return known;
    }
}
