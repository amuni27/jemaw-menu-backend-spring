package com.agafari.com.billing.webhook;

import com.agafari.com.billing.port.InboundEvent;
import com.agafari.com.jobs.JobQueue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** AGF SUB-R4-07 (BD-5, DD §8.1): stores a verified event and its job in one commit, before the provider is acknowledged. */
@Service
public class InboxService {

    public static final String JOB_TYPE = "inbox.process";
    /** AGF SUB-R4-14 (G4): reserved for internal reconciliation events; a provider webhook may never use it. */
    public static final String INTERNAL_PREFIX = "agafari-reconciliation:";
    public static final String INTERNAL_TYPE = "agafari.reconciliation.payment";
    private static final tools.jackson.databind.ObjectMapper INTERNAL_JSON = new tools.jackson.databind.ObjectMapper();

    private final JdbcTemplate jdbc;
    private final JobQueue jobs;
    private final TransactionTemplate transaction;

    public InboxService(JdbcTemplate jdbc, JobQueue jobs, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.transaction = new TransactionTemplate(transactions);
    }

    /**
     * AGF SUB-R4-14 (G4): a durable internal reconciliation event for a payment the provider listed, idempotent per provider
     * and payment. It carries no signature and is never evidence: it only names what {@code inbox.process} re-fetches. Its
     * identity is in a namespace no signed webhook may use ({@link #isReserved}). Returns the event's id.
     */
    public String storeInternal(String provider, String paymentRef) {
        if (paymentRef == null || paymentRef.isBlank() || paymentRef.length() > 255 - INTERNAL_PREFIX.length()) throw new IllegalArgumentException("A payment reference is required");
        String eventId = INTERNAL_PREFIX + paymentRef;
        return transaction.execute(s -> {
            String id = UUID.randomUUID().toString();
            String payload = INTERNAL_JSON.writeValueAsString(java.util.Map.of("source", "AGAFARI_RECONCILIATION", "payment_ref", paymentRef));
            int inserted = jdbc.update("insert into provider_event (id, provider, event_id, type, payload) values (?, ?, ?, ?, ?::jsonb) on conflict (provider, event_id) do nothing",
                    id, provider, eventId, INTERNAL_TYPE, payload);
            if (inserted == 0) return jdbc.queryForObject("select id from provider_event where provider = ? and event_id = ?", String.class, provider, eventId);
            jobs.enqueue(JOB_TYPE, provider + ":" + eventId, new InboxProcessPayload(id));
            return id;
        });
    }

    /** AGF SUB-R4-14 (G4): an identity only Agafari's reconciliation may use; a signed webhook claiming it is refused. */
    public static boolean isReserved(InboundEvent event) {
        return event.eventId().startsWith(INTERNAL_PREFIX) || INTERNAL_TYPE.equals(event.type());
    }

    /** AGF SUB-R4-14: whether a stored event is an internal reconciliation event. */
    public static boolean isInternal(String eventId, String type) {
        return eventId != null && eventId.startsWith(INTERNAL_PREFIX) && INTERNAL_TYPE.equals(type);
    }

    /** @return false if this provider event was already stored */
    public boolean store(String provider, InboundEvent event, byte[] rawBody) {
        return Boolean.TRUE.equals(transaction.execute(s -> {
            String id = UUID.randomUUID().toString();
            int inserted = jdbc.update("insert into provider_event (id, provider, event_id, type, payload) values (?, ?, ?, ?, ?::jsonb) on conflict (provider, event_id) do nothing",
                    id, provider, event.eventId(), event.type(), new String(rawBody, StandardCharsets.UTF_8));
            if (inserted == 0) return false;
            jobs.enqueue(JOB_TYPE, provider + ":" + event.eventId(), new InboxProcessPayload(id));
            return true;
        }));
    }
}
