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
        // AGF R4-16-D1: a paused provider's jobs of a multi-provider type are skipped one by one; nothing changes when none is paused
        List<String> paused = pausedUntil.keySet().stream().filter(this::isPaused).sorted().toList();
        StringBuilder skip = new StringBuilder();
        List<Object> args = new ArrayList<>(List.of(JobFailurePolicy.MAX_CLAIMS, types));
        if (!paused.isEmpty()) {
            String pausedArray = "{" + String.join(",", paused) + "}";
            for (JobHandler<?> h : handlers.values()) {
                if (h.provider() != null || h.providerSql() == null) continue;
                skip.append(" and not (j.type = ? and coalesce(").append(h.providerSql()).append(", '') = any (?::text[]))");
                args.add(h.type());
                args.add(pausedArray);
            }
        }
        args.add(capacity);
        List<Map<String, Object>> claimed = jdbc.queryForList("""
                with due as (select j.id from job j where j.status = 'PENDING' and j.run_at <= now() and j.claims < ? and j.type = any (string_to_array(?, ','))"""
                + skip + """

                             order by j.run_at for update of j skip locked limit ?)
                update job j set status = 'RUNNING', claims = claims + 1, claim_token = gen_random_uuid()::text, lease_until = now() + interval '10 minutes',
                                 first_attempt_at = coalesce(first_attempt_at, now())
                from due where j.id = due.id
                returning j.id, j.type, j.payload::text as payload, j.claims, j.claim_token, j.first_attempt_at
                """, args.toArray());
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
                update job set status = 'DEAD', finished_at = now(), claim_token = null, lease_until = null,
                               last_error = case when status = 'RUNNING' then ? else coalesce(last_error, ?) end
                where claims >= ? and (status = 'PENDING' or (status = 'RUNNING' and lease_until < now())) returning id, type
                """, WORKER_LOST, WORKER_LOST, JobFailurePolicy.MAX_CLAIMS);
        dead.forEach(d -> log.error("event=job.dead id={} type={} reason=claims_exhausted", d.get("id"), d.get("type")));
        jdbc.update("update job set status = 'PENDING', claim_token = null, lease_until = null, last_error = ? where status = 'RUNNING' and lease_until < now() and claims < ?", WORKER_LOST, JobFailurePolicy.MAX_CLAIMS);
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
        if (!providerFailures.isEmpty()) {
            String provider = providerOf(handler, id);
            if (provider != null) providerFailures.remove(provider);
        }
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
        trackProvider(providerOf(handler, id), kind);
        Duration cap = JobFailurePolicy.transientCap(type, handler.transientCap());
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

    /**
     * AGF R4-16-D1: the provider a job counts against: the handler's type-level provider, else the job's own provider resolved
     * from persisted data by {@link JobHandler#providerSql()}; null if it has none or can't be resolved (then nothing is
     * counted, as before).
     */
    private String providerOf(JobHandler<?> handler, String jobId) {
        if (handler.provider() != null) return handler.provider();
        if (handler.providerSql() == null) return null;
        try {
            List<String> found = jdbc.queryForList("select " + handler.providerSql() + " from job j where j.id = ?", String.class, jobId);
            return found.isEmpty() ? null : found.get(0);
        } catch (RuntimeException e) {
            log.warn("event=job.provider_unresolved id={} type={} error={}", jobId, handler.type(), e.getClass().getSimpleName());
            return null;
        }
    }

    private void trackProvider(String provider, JobFailurePolicy.Kind kind) {
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
