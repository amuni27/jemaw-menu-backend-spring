package com.agafari.com.jobs;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.UUID;

/** AGF SUB-R2-08 (BD-5, BD-18): adds a job in the caller's transaction, so it exists only if the business change commits. */
@Component
public class JobQueue {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JobQueue(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** The same key and payload is a no-op; the same key with another payload throws {@link JobKeyConflict}. */
    public void enqueue(String type, String key, Object payload) {
        enqueue(type, key, payload, Duration.ZERO);
    }

    /** AGF SUB-R4-05: not due until {@code delay} has passed, in database time. */
    public void enqueue(String type, String key, Object payload, Duration delay) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("A job must be enqueued inside a transaction");
        JsonNode tree = mapper.valueToTree(payload);
        String hash = PayloadHash.of(tree);
        int inserted = jdbc.update("insert into job (id, type, key, payload, payload_hash, run_at) values (?, ?, ?, ?::jsonb, ?, now() + (? * interval '1 second')) on conflict (type, key) do nothing",
                UUID.randomUUID().toString(), type, key, PayloadHash.canonical(tree), hash, delay.toSeconds());
        if (inserted == 0 && !hash.equals(jdbc.queryForObject("select payload_hash from job where type = ? and key = ?", String.class, type, key))) {
            throw new JobKeyConflict(type, key);
        }
    }
}
