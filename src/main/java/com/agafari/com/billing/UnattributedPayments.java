package com.agafari.com.billing;

import com.agafari.com.billing.port.ProviderBilling;
import com.agafari.com.billing.port.VerifiedPayment;
import com.agafari.com.billing.webhook.InboxService;
import com.agafari.com.service.MonetizationClock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AGF gate 3 (register §89 G3-B..E, §101, §102): the exceptions written <b>in the transaction of a quarantine</b>.
 * <ul>
 *   <li>{@link #recordUnattributed}: a <b>verified successful</b> payment that can't be attributed to a restaurant (C-2) gets
 *   exactly one restaurant-less {@code UNATTRIBUTED_PAYMENT} per payment (V23_7 identity: provider, payment reference,
 *   code). A later event of the same payment, by webhook or reconciliation, is linked to it by an audit entry, so every
 *   event id stays traceable. No restaurant is inferred, nothing is applied, refunded or written to the ledger.</li>
 *   <li>{@link #recordUnverified} (K-1): a payment-path verification failure gets one restaurant-less
 *   {@code PAYMENT_OUTCOME_PENDING_REVIEW} per event, marked as an <b>unverified outcome</b>: no amount, no payment identity,
 *   not customer funds; reviewed within 24 elapsed hours.</li>
 * </ul>
 * The reason is a stable {@link Reason}; the event keeps its original quarantine text.
 */
@Component
public class UnattributedPayments {

    public static final String UNATTRIBUTED = "UNATTRIBUTED_PAYMENT";
    public static final String PENDING_REVIEW = DeadJobEscalation.CODE;
    /** K-1: the review deadline of an unverified payment outcome (the G3-E basis). */
    public static final Duration UNVERIFIED_REVIEW = Duration.ofHours(24);

    /** Why a verified successful payment can't be attributed. The text is the one the quarantine has always recorded. */
    public enum Reason {
        NO_AGAFARI_REFERENCE("the payment carries no checkout or restaurant"),
        UNKNOWN_CHECKOUT("the payment names an unknown checkout"),
        OTHER_RESTAURANT("the checkout belongs to another restaurant"),
        OTHER_PROVIDER("the checkout belongs to another provider"),
        SESSION_MISMATCH("the provider session reference is missing or differs"),
        SESSION_OF_ANOTHER_CHECKOUT("the provider session reference belongs to another checkout"),
        NO_MARKET_ACCOUNT("the restaurant has no billing account in this market"),
        NO_CHECKOUT("no checkout"),
        /** C-1: a renewal payment of a subscription that is no Agafari PROVIDER subscription (renewal row R1) */
        UNKNOWN_SUBSCRIPTION("the provider subscription is no Agafari PROVIDER subscription");

        private final String text;

        Reason(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    private final JdbcTemplate jdbc;
    private final ExceptionService exceptions;
    private final MonetizationClock clock;

    public UnattributedPayments(JdbcTemplate jdbc, ExceptionService exceptions, MonetizationClock clock) {
        this.jdbc = jdbc;
        this.exceptions = exceptions;
        this.clock = clock;
    }

    /** In the caller's transaction, right after the event was marked QUARANTINED. */
    void recordUnattributed(String eventRowId, String provider, VerifiedPayment verified, Reason reason, String rowCode, String quarantineReason) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("fundsStatus", "COLLECTED_VERIFIED");
        facts.put("reasonCode", reason.name());
        facts.put("rowCode", rowCode);
        facts.put("quarantineReason", quarantineReason);
        facts.put("provider", provider);
        facts.put("providerPaymentRef", verified.providerPaymentRef());
        facts.put("amountMinor", verified.amount().minor());
        facts.put("currency", verified.amount().currency().name());
        facts.put("paidAt", String.valueOf(verified.paidAt()));
        String session = verified.metadata().get(PaymentMetadata.PROVIDER_SESSION_REF);
        if (session != null) facts.put("sessionRef", session);
        ProviderBilling b = verified.billing();
        if (b != null) {
            if (b.subscriptionRef() != null) facts.put("subscriptionRef", b.subscriptionRef());
            if (b.customerRef() != null) facts.put("customerRef", b.customerRef());
        }
        String source = eventFacts(eventRowId, facts);
        boolean created = exceptions.createForPayment(UNATTRIBUTED, eventRowId, verified.amount().minor(), verified.amount().currency().name(),
                verified.paidAt() == null ? null : verified.paidAt().truncatedTo(ChronoUnit.MICROS), facts, clock.now(), provider, verified.providerPaymentRef());
        if (!created) exceptions.linkEventToPayment(provider, verified.providerPaymentRef(), UNATTRIBUTED, eventRowId, source);
    }

    /** K-1, in the caller's transaction, right after the event was marked QUARANTINED. */
    void recordUnverified(String eventRowId, String requestedRef, String quarantineReason) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("fundsStatus", "UNVERIFIED");
        facts.put("quarantineReason", quarantineReason);
        facts.put("requestedPaymentRef", requestedRef);
        eventFacts(eventRowId, facts);
        exceptions.create(null, PENDING_REVIEW, null, null, eventRowId, null, null, null, null, false, "OPEN", facts, clock.now(), null, UNVERIFIED_REVIEW);
    }

    /** The event's identity, never its body; returns its source (WEBHOOK or RECONCILIATION). */
    private String eventFacts(String eventRowId, Map<String, Object> facts) {
        List<Map<String, Object>> rows = jdbc.queryForList("select event_id, type from provider_event where id = ?", eventRowId);
        if (rows.isEmpty()) return "UNKNOWN";
        String eventId = (String) rows.get(0).get("event_id"), type = (String) rows.get(0).get("type");
        String source = InboxService.isInternal(eventId, type) ? "RECONCILIATION" : "WEBHOOK";
        facts.put("eventId", eventId);
        facts.put("eventType", type);
        facts.put("source", source);
        return source;
    }
}
