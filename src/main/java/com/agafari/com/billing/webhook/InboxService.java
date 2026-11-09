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

    private final JdbcTemplate jdbc;
    private final JobQueue jobs;
    private final TransactionTemplate transaction;

    public InboxService(JdbcTemplate jdbc, JobQueue jobs, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.transaction = new TransactionTemplate(transactions);
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
