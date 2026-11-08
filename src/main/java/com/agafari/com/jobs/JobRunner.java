package com.agafari.com.jobs;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AGF SUB-R2-08 (DD §9.2, BD-18): claims jobs in one short statement ({@code SKIP LOCKED}, claims counted at claim), runs
 * handlers outside any transaction, and records the result with an update fenced by the claim token. Leases and
 * {@code run_at} use database time. Started only by {@link JobPoller} ({@code app.jobs.enabled}, default false).
 */
@Slf4j
@Component
public class JobRunner {

    private static final int MAX_BATCH = 20;
    private static final int PRUNE_BATCH = 1000;
    private static final int MAX_ERROR_LENGTH = 1000;
    private static final int PROVIDER_FAILURES_BEFORE_PAUSE = 10;
    private static final Duration PROVIDER_PAUSE = Duration.ofMinutes(5);
    private static final String WORKER_LOST = "worker lost: lease expired";

    private final JdbcTemplate jdbc;
    private final Map<String, JobHandler<?>> handlers;
    private final ObjectMapper mapper;
    private final int workers;
    private final Clock clock;
    private final ExecutorService pool;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final Map<String, AtomicInteger> providerFailures = new ConcurrentHashMap<>();
    private final Map<String, Instant> pausedUntil = new ConcurrentHashMap<>();

    @Autowired
    public JobRunner(JdbcTemplate jdbc, ObjectProvider<JobHandler<?>> handlers, ObjectMapper mapper, @Value("${app.jobs.workers:4}") int workers) {
        this(jdbc, handlers.stream().toList(), mapper, workers, Clock.systemUTC());
    }

    public JobRunner(JdbcTemplate jdbc, List<JobHandler<?>> handlers, ObjectMapper mapper, int workers, Clock clock) {
        this.jdbc = jdbc;
        this.handlers = handlers.stream().collect(Collectors.toMap(JobHandler::type, Function.identity()));
        this.mapper = mapper;
        this.workers = workers;
        this.clock = clock;
        this.pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "job-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    /** Recovers expired leases, claims as many due jobs as there are idle workers and starts them. */
    public List<Future<?>> pollOnce() {
        recoverExpiredLeases();
        int capacity = Math.min(MAX_BATCH, workers - inFlight.get());
        String types = handlers.values().stream().filter(h -> h.provider() == null || !isPaused(h.provider())).map(JobHandler::type).collect(Collectors.joining(","));
        if (capacity <= 0 || types.isEmpty()) return List.of();
        List<Map<String, Object>> claimed = jdbc.queryForList("""
                with due as (select id from job where status = 'PENDING' and run_at <= now() and type = any (string_to_array(?, ','))
                             order by run_at for update skip locked limit ?)
                update job j set status = 'RUNNING', claims = claims + 1, claim_token = gen_random_uuid()::text, lease_until = now() + interval '10 minutes',
                                 first_attempt_at = coalesce(first_attempt_at, now())
                from due where j.id = due.id
                returning j.id, j.type, j.payload::text as payload, j.claims, j.claim_token, j.first_attempt_at
                """, types, capacity);
        List<Future<?>> started = new ArrayList<>();
        for (Map<String, Object> row : claimed) {
            inFlight.incrementAndGet();
            started.add(pool.submit(() -> {
                try {
                    run(row);
                } finally {
                    inFlight.decrementAndGet();
                }
            }));
        }
        return started;
    }

    public boolean isPaused(String provider) {
        Instant until = pausedUntil.get(provider);
        return until != null && clock.instant().isBefore(until);
    }

    /** Removes old finished non-financial jobs; financial keys are never pruned, so they can never be reused. */
    public int prune() {
        int total = 0;
        int removed;
        do {
            removed = jdbc.update("""
                    delete from job where id in (select id from job where status = 'DONE' and finished_at < now() - interval '30 days'
                        and type not like 'refund.%' and type not like 'checkout.%' and type <> 'inbox.process' limit ?)
                    """, PRUNE_BATCH);
            total += removed;
        } while (removed == PRUNE_BATCH);
        return total;
    }

    private void recoverExpiredLeases() {
        List<Map<String, Object>> dead = jdbc.queryForList("""
                update job set status = 'DEAD', finished_at = now(), claim_token = null, lease_until = null, last_error = ?
                where status = 'RUNNING' and lease_until < now() and claims >= ? returning id, type
                """, WORKER_LOST, JobFailurePolicy.MAX_CLAIMS);
        dead.forEach(d -> log.error("event=job.dead id={} type={} reason=claims_exhausted", d.get("id"), d.get("type")));
        jdbc.update("update job set status = 'PENDING', claim_token = null, lease_until = null, last_error = ? where status = 'RUNNING' and lease_until < now()", WORKER_LOST);
    }

    /** An Error (a killed worker) deliberately escapes: nothing is recorded and the lease expires. */
    private void run(Map<String, Object> row) {
        String id = (String) row.get("id");
        String token = (String) row.get("claim_token");
        JobHandler<?> handler = handlers.get((String) row.get("type"));
        try {
            execute(handler, (String) row.get("payload"));
        } catch (Exception e) {
            fail(handler, row, e);
            return;
        }
        if (handler.provider() != null) providerFailures.remove(handler.provider());
        fenced(jdbc.update("update job set status = 'DONE', finished_at = now(), lease_until = null, last_error = null, last_error_kind = null where id = ? and claim_token = ? and status = 'RUNNING'", id, token), id);
    }

    private <T> void execute(JobHandler<T> handler, String payload) throws Exception {
        handler.handle(mapper.readValue(payload, handler.payloadType()));
    }

    private void fail(JobHandler<?> handler, Map<String, Object> row, Exception e) {
        String id = (String) row.get("id");
        String token = (String) row.get("claim_token");
        String type = (String) row.get("type");
        JobFailurePolicy.Kind kind = JobFailurePolicy.classify(e);
        trackProvider(handler, kind);
        Duration cap = JobFailurePolicy.isFinancial(type) ? JobFailurePolicy.FINANCIAL_TRANSIENT_CAP : handler.transientCap();
        Instant now = jdbc.queryForObject("select now()", Timestamp.class).toInstant();
        int claims = ((Number) row.get("claims")).intValue();
        JobFailurePolicy.Decision decision = JobFailurePolicy.decide(kind, claims, ((Timestamp) row.get("first_attempt_at")).toInstant(), now, cap);
        String error = errorText(e);
        if (decision.dead()) {
            int updated = jdbc.update("update job set status = 'DEAD', finished_at = now(), lease_until = null, last_error = ?, last_error_kind = ? where id = ? and claim_token = ? and status = 'RUNNING'",
                    error, kind.name(), id, token);
            fenced(updated, id);
            if (updated == 1) log.error("event=job.dead id={} type={} claims={} kind={}", id, type, claims, kind);
        } else {
            fenced(jdbc.update("update job set status = 'PENDING', run_at = now() + (? * interval '1 second'), claim_token = null, lease_until = null, last_error = ?, last_error_kind = ? "
                    + "where id = ? and claim_token = ? and status = 'RUNNING'", decision.retryAfter().toSeconds(), error, kind.name(), id, token), id);
        }
    }

    private void trackProvider(JobHandler<?> handler, JobFailurePolicy.Kind kind) {
        String provider = handler.provider();
        if (provider == null) return;
        if (kind != JobFailurePolicy.Kind.TRANSIENT) {
            providerFailures.remove(provider);
        } else if (providerFailures.computeIfAbsent(provider, p -> new AtomicInteger()).incrementAndGet() >= PROVIDER_FAILURES_BEFORE_PAUSE) {
            providerFailures.remove(provider);
            pausedUntil.put(provider, clock.instant().plus(PROVIDER_PAUSE));
            log.warn("event=job.provider_paused provider={} minutes={}", provider, PROVIDER_PAUSE.toMinutes());
        }
    }

    private static void fenced(int updated, String id) {
        if (updated == 0) log.warn("event=job.stale_result id={}", id);
    }

    private static String errorText(Exception e) {
        String text = e.getClass().getSimpleName() + ": " + e.getMessage();
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
