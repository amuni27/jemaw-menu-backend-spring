package com.agafari.com.subscription;

import com.agafari.com.jobs.JobHandler;
import com.agafari.com.jobs.JobRunner;
import com.agafari.com.jobs.PermanentJobException;
import com.agafari.com.jobs.TransientJobException;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.Entitlements;
import com.agafari.com.service.EntitlementService;
import com.agafari.com.service.GrandfatheringService;
import com.agafari.com.service.MonetizationLocks;
import com.agafari.com.support.AbstractIntegrationTest;
import com.agafari.com.support.TestDataFactory.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF SUB-R2-10: period-end persistence through the accepted job runner, and the pre-switch report. Grace of 7 days is a fixture. */
class PeriodEndJobIntegrationTest extends AbstractIntegrationTest {

    private static final String AUDIT = "SUBSCRIPTION_PERIOD_ENDED";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PeriodEndJob job;
    @Autowired private EffectiveStateEvaluator evaluator;
    @Autowired private EntitlementService entitlements;
    @Autowired private GrandfatheringService grandfathering;
    @Autowired private ObjectMapper mapper;
    @Autowired private ApplicationContext context;

    private final List<JobRunner> runners = new ArrayList<>();
    private User admin;
    private String bearer;
    private Tenant owner;

    @BeforeEach
    void setUp() {
        admin = data.admin("period-admin");
        bearer = data.adminBearer(admin);
        owner = data.tenant("period-owner");
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        evaluator.setPeriodEndEnabled(true);
    }

    @AfterEach
    void reset() {
        runners.forEach(JobRunner::shutdown);
        evaluator.setPeriodEndEnabled(false);
        entitlements.setEnabled(false);
        grandfathering.setEnabled(false);
    }

    private static Instant days(int n) {
        return Instant.now().plus(n, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    }

    private String row(String businessId, String source, String status, String plan, Instant end, Integer grace, boolean openEnded, Instant endedAt) {
        String id = UUID.randomUUID().toString();
        boolean provider = "PROVIDER".equals(source);
        // AGF SUB-R4-02: a fixture row has no payment evidence, so the commit check stays off for this test; the per-test reset restores it.
        if (provider) jdbc.execute("alter table business_subscription disable trigger provider_row_has_evidence");
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, created_by_user_id, updated_at, updated_by_user_id, grace_days, open_ended, ended_at, row_version) "
                        + "values (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)", id, businessId, plan, status, end == null ? null : Timestamp.from(end), source,
                Timestamp.from(days(-40)), admin.getId(), Timestamp.from(days(-40)), admin.getId(), grace, openEnded, endedAt == null ? null : Timestamp.from(endedAt));
        return id;
    }

    private String manual(Instant end) {
        return row(owner.businessId(), "MANUAL", "ACTIVE", "MODEL_4", end, null, false, null);
    }

    private JobRunner runner(JobHandler<?> handler) {
        JobRunner r = new JobRunner(jdbc, List.of(handler), mapper, 4, Clock.systemUTC());
        runners.add(r);
        return r;
    }

    /** The real job, with something added before or after it. */
    private JobHandler<PeriodEndJob.Payload> wrapped(Consumer<PeriodEndJob.Payload> before, Consumer<PeriodEndJob.Payload> after) {
        return new JobHandler<>() {
            @Override public String type() { return PeriodEndJob.TYPE; }
            @Override public Class<PeriodEndJob.Payload> payloadType() { return PeriodEndJob.Payload.class; }
            @Override public void handle(PeriodEndJob.Payload p) {
                before.accept(p);
                job.handle(p);
                after.accept(p);
            }
        };
    }

    private static void drain(List<Future<?>> futures) throws Exception {
        for (Future<?> f : futures) {
            try {
                f.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException expected) {
                // a killed worker
            }
        }
    }

    private Map<String, Object> stored(String id) {
        return jdbc.queryForMap("select status, ended_at, ended_reason, row_version from business_subscription where id = ?", id);
    }

    private int audits(String id) {
        return jdbc.queryForObject("select count(*) from admin_audit_event where action = ? and target_id = ?", Integer.class, AUDIT, id);
    }

    private int jobs() {
        return jdbc.queryForObject("select count(*) from job where type = ?", Integer.class, PeriodEndJob.TYPE);
    }

    private Map<String, Object> jobRow() {
        return jdbc.queryForMap("select status, claims, last_error_kind from job where type = ?", PeriodEndJob.TYPE);
    }

    private void due() {
        jdbc.update("update job set run_at = now() where status = 'PENDING'");
    }

    private void expireLeases() {
        jdbc.update("update job set lease_until = now() - interval '1 second' where status = 'RUNNING'");
    }

    private void assertPersistedOnce(String id, Instant end) {
        Map<String, Object> row = stored(id);
        assertThat(row).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "PERIOD_END").containsEntry("row_version", 1);
        assertThat(((Timestamp) row.get("ended_at")).toInstant()).isEqualTo(end);
        assertThat(audits(id)).isEqualTo(1);
    }

    // ---- which rows are enqueued

    @Test
    void onlyEffectivelyDueNonTrialRowsAreEnqueued() {
        Tenant t2 = data.tenant("p2"), t3 = data.tenant("p3"), t4 = data.tenant("p4"), t5 = data.tenant("p5"), t6 = data.tenant("p6"), t7 = data.tenant("p7");
        String due = manual(days(-1));
        String ownerDue = row(t2.businessId(), "OWNER", "ACTIVE", "MODEL_2", days(-1), null, false, null);
        row(t3.businessId(), "MANUAL", "ACTIVE", "MODEL_4", days(5), null, false, null);
        row(t4.businessId(), "OWNER", "ACTIVE", "MODEL_2", null, null, false, null);
        row(t5.businessId(), "TRIAL", "TRIAL", "MODEL_1", days(-1), null, false, null);
        row(t6.businessId(), "MANUAL", "ACTIVE", "MODEL_4", days(-1), null, true, null);
        row(t7.businessId(), "MANUAL", "EXPIRED", "MODEL_4", days(-5), null, false, days(-5));
        assertThat(job.enqueueDue()).isEqualTo(2);
        assertThat(jdbc.queryForList("select split_part(key, '@', 1) from job where type = ?", String.class, PeriodEndJob.TYPE)).containsExactlyInAnyOrder(due, ownerDue);
    }

    @Test
    void enqueueingTwiceCreatesOneJob() {
        manual(days(-1));
        job.enqueueDue();
        job.enqueueDue();
        assertThat(jobs()).isEqualTo(1);
    }

    @Test
    void theFlagOffEnqueuesNothingAndChangesNothing() throws Exception {
        String id = manual(days(-1));
        evaluator.setPeriodEndEnabled(false);
        assertThat(job.enqueueDue()).isZero();
        job.handle(new PeriodEndJob.Payload(owner.businessId(), id, days(-1)));
        assertThat(stored(id)).containsEntry("status", "ACTIVE").containsEntry("row_version", 0);
        assertThat(audits(id)).isZero();
    }

    @Test
    void theFlagOffLeavesGrandfatheringAloneInTheHandlerToo() {
        grandfathering.setEnabled(true);
        String id = row(owner.businessId(), "MANUAL", "ACTIVE", "MODEL_3", days(-3), null, false, null);
        evaluator.setPeriodEndEnabled(false);
        job.handle(new PeriodEndJob.Payload(owner.businessId(), id, days(-3)));
        assertThat(stored(id)).containsEntry("status", "ACTIVE").containsEntry("row_version", 0);
    }

    @Test
    void aDifferentPeriodEndGetsItsOwnJob() {
        String id = manual(days(-1));
        job.enqueueDue();
        jdbc.update("update business_subscription set period_end = ? where id = ?", Timestamp.from(days(-2)), id);
        job.enqueueDue();
        assertThat(jobs()).isEqualTo(2);
    }

    @Test
    void theCadenceExistsOnlyWithTheJobFlagOn() {
        assertThat(context.getBeanNamesForType(PeriodEndCadence.class)).isEmpty();
        ApplicationContextRunner contexts = new ApplicationContextRunner().withBean(PeriodEndJob.class, () -> mock(PeriodEndJob.class)).withUserConfiguration(PeriodEndCadence.class);
        contexts.run(c -> assertThat(c).doesNotHaveBean(PeriodEndCadence.class));
        contexts.withPropertyValues("app.jobs.enabled=true").run(c -> assertThat(c).hasSingleBean(PeriodEndCadence.class));
    }

    // ---- persistence through the runner (AC1)

    @Test
    void theRunnerPersistsADueRowOnce() throws Exception {
        Instant end = days(-1);
        String id = manual(end);
        job.enqueueDue();
        JobRunner r = runner(job);
        drain(r.pollOnce());
        assertPersistedOnce(id, end);
        assertThat(jobRow()).containsEntry("status", "DONE").containsEntry("claims", 1);
    }

    @Test
    void runningTheJobTwicePersistsOnceWithOneAudit() throws Exception {
        Instant end = days(-1);
        String id = manual(end);
        job.enqueueDue();
        drain(runner(job).pollOnce());
        job.handle(new PeriodEndJob.Payload(owner.businessId(), id, end));
        assertThat(job.enqueueDue()).isZero();
        assertPersistedOnce(id, end);
        assertThat(jobs()).isEqualTo(1);
    }

    @Test
    void concurrentHandlersOfOneRowPersistItOnce() throws Exception {
        Instant end = days(-1);
        String id = manual(end);
        CyclicBarrier barrier = new CyclicBarrier(6);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<?>> all = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                all.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    job.handle(new PeriodEndJob.Payload(owner.businessId(), id, end));
                    return null;
                }));
            }
            for (Future<?> f : all) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertPersistedOnce(id, end);
    }

    @Test
    void twoRunnersPersistEachRowOnce() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 20; i++) ids.add(row(data.tenant("many" + i).businessId(), "MANUAL", "ACTIVE", "MODEL_4", days(-1), null, false, null));
        job.enqueueDue();
        JobRunner r1 = runner(job), r2 = runner(job);
        ExecutorService pollers = Executors.newFixedThreadPool(2);
        try {
            int guard = 0;
            while (jdbc.queryForObject("select count(*) from job where status <> 'DONE'", Integer.class) > 0 && guard++ < 100) {
                Future<List<Future<?>>> f1 = pollers.submit(r1::pollOnce);
                Future<List<Future<?>>> f2 = pollers.submit(r2::pollOnce);
                drain(f1.get(30, TimeUnit.SECONDS));
                drain(f2.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pollers.shutdownNow();
        }
        for (String id : ids) {
            assertThat(stored(id)).containsEntry("status", "EXPIRED").containsEntry("row_version", 1);
            assertThat(audits(id)).isEqualTo(1);
        }
    }

    @Test
    void aProviderRowInGraceIsPersistedPastDue() throws Exception {
        Instant end = days(-1);
        String id = row(owner.businessId(), "PROVIDER", "ACTIVE", "MODEL_4", end, 7, false, null);
        job.enqueueDue();
        drain(runner(job).pollOnce());
        assertThat(stored(id)).containsEntry("status", "PAST_DUE").containsEntry("row_version", 1);
    }

    // ---- crash, restart, limits, fencing

    @Test
    void aWorkerKilledBeforeTheWriteIsRecovered() throws Exception {
        Instant end = days(-1);
        String id = manual(end);
        job.enqueueDue();
        AtomicInteger runs = new AtomicInteger();
        JobRunner r = runner(wrapped(p -> { if (runs.incrementAndGet() == 1) throw new Error("killed before the write"); }, p -> { }));
        drain(r.pollOnce());
        assertThat(stored(id)).containsEntry("status", "ACTIVE");
        expireLeases();
        drain(r.pollOnce());
        assertPersistedOnce(id, end);
        assertThat(jobRow()).containsEntry("status", "DONE").containsEntry("claims", 2);
    }

    @Test
    void aWorkerKilledAfterTheCommitDoesNotPersistOrAuditTwice() throws Exception {
        Instant end = days(-1);
        String id = manual(end);
        job.enqueueDue();
        AtomicInteger runs = new AtomicInteger();
        JobRunner r = runner(wrapped(p -> { }, p -> { if (runs.incrementAndGet() == 1) throw new Error("killed after the commit"); }));
        drain(r.pollOnce());
        expireLeases();
        drain(r.pollOnce());
        assertPersistedOnce(id, end);
        assertThat(jobRow()).containsEntry("status", "DONE").containsEntry("claims", 2);
    }

    @Test
    void aRestartedRunnerPicksUpUnfinishedWork() throws Exception {
        Instant end = days(-1);
        String id = manual(end);
        job.enqueueDue();
        JobRunner stopped = runner(job);
        stopped.shutdown();
        drain(runner(job).pollOnce());
        assertPersistedOnce(id, end);
    }

    @Test
    void eightFailuresEndTheJobDeadAndEntitlementStillEnds() throws Exception {
        String id = manual(days(-1));
        job.enqueueDue();
        entitlements.setEnabled(true);
        AtomicInteger runs = new AtomicInteger();
        JobRunner r = runner(wrapped(p -> { runs.incrementAndGet(); throw new PermanentJobException("no", null); }, p -> { }));
        for (int i = 0; i < 8; i++) {
            drain(r.pollOnce());
            due();
        }
        assertThat(drain0(r)).isZero();
        assertThat(jobRow()).containsEntry("status", "DEAD").containsEntry("claims", 8);
        assertThat(runs.get()).isEqualTo(8);
        assertThat(stored(id)).containsEntry("status", "ACTIVE").containsEntry("row_version", 0);
        assertThat(entitlements.forBusiness(owner.businessId())).isEqualTo(Entitlements.unrestricted());
    }

    private int drain0(JobRunner r) throws Exception {
        List<Future<?>> f = r.pollOnce();
        drain(f);
        return f.size();
    }

    @Test
    void transientFailuresUseTheTwentyFourHourCap() throws Exception {
        manual(days(-1));
        job.enqueueDue();
        JobRunner r = runner(wrapped(p -> { throw new TransientJobException("db", null); }, p -> { }));
        drain(r.pollOnce());
        jdbc.update("update job set first_attempt_at = now() - interval '23 hours 59 minutes', run_at = now()");
        drain(r.pollOnce());
        assertThat(jobRow()).containsEntry("status", "PENDING");
        jdbc.update("update job set first_attempt_at = now() - interval '24 hours 1 second', run_at = now()");
        drain(r.pollOnce());
        assertThat(jobRow()).containsEntry("status", "DEAD").containsEntry("last_error_kind", "TRANSIENT");
    }

    @Test
    void aStaleWorkerAndItsReplacementPersistOnce() throws Exception {
        Instant end = days(-1);
        String id = manual(end);
        job.enqueueDue();
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobRunner w1 = runner(wrapped(p -> {
            running.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }, p -> { }));
        List<Future<?>> stale = w1.pollOnce();
        assertThat(running.await(10, TimeUnit.SECONDS)).isTrue();
        expireLeases();
        drain(runner(job).pollOnce());
        assertThat(jobRow()).containsEntry("status", "DONE").containsEntry("claims", 2);
        release.countDown();
        drain(stale);
        assertPersistedOnce(id, end);
        assertThat(jobRow()).containsEntry("status", "DONE").containsEntry("claims", 2);
    }

    // ---- against admin changes

    private int extend(int rowVersion, Instant to) throws Exception {
        return mockMvc.perform(patch("/api/admin/businesses/{id}/subscription", owner.businessId()).header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"periodEnd\":\"" + to + "\",\"rowVersion\":" + rowVersion + "}")).andReturn().getResponse().getStatus();
    }

    @Test
    void anAdminExtensionBeforeTheJobRunsWins() throws Exception {
        Instant end = days(-1);
        String id = manual(end);
        job.enqueueDue();
        assertThat(extend(0, days(30))).isEqualTo(200);
        drain(runner(job).pollOnce());
        assertThat(stored(id)).containsEntry("status", "ACTIVE").containsEntry("row_version", 1);
        assertThat(audits(id)).isZero();
        assertThat(jobRow()).containsEntry("status", "DONE");
    }

    @Test
    void anExtensionRacingTheJobHasOneConsistentOutcome() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                owner = data.tenant("period-race-" + round);
                Instant end = days(-1);
                String id = manual(end);
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Integer> admin = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return extend(0, days(30));
                });
                Future<?> handler = pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    job.handle(new PeriodEndJob.Payload(owner.businessId(), id, end));
                    return null;
                });
                int status = admin.get(30, TimeUnit.SECONDS);
                handler.get(30, TimeUnit.SECONDS);
                Map<String, Object> row = stored(id);
                assertThat(row).as("round " + round).containsEntry("row_version", 1);
                if (status == 200) {
                    assertThat(row).as("round " + round).containsEntry("status", "ACTIVE");
                    assertThat(audits(id)).as("round " + round).isZero();
                } else {
                    assertThat(status).as("round " + round).isIn(404, 409);
                    assertPersistedOnce(id, end);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- trials and grandfathering

    @Test
    void aTrialIsLeftToItsOwnExpiry() throws Exception {
        String id = row(owner.businessId(), "TRIAL", "TRIAL", "MODEL_1", days(-1), null, false, null);
        assertThat(job.enqueueDue()).isZero();
        job.handle(new PeriodEndJob.Payload(owner.businessId(), id, days(-1)));
        assertThat(stored(id)).containsEntry("status", "TRIAL").containsEntry("row_version", 0);
    }

    @Test
    void aGrandfatheredRowEndsThroughItsOwnService() throws Exception {
        grandfathering.setEnabled(true);
        Instant end = days(-3);
        String id = row(owner.businessId(), "MANUAL", "ACTIVE", "MODEL_3", end, null, false, null);
        job.enqueueDue();
        drain(runner(job).pollOnce());
        assertThat(stored(id)).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "GRANDFATHERED_END");
        assertThat(audits(id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = 'GRANDFATHERING_ENDED' and target_id = ?", Integer.class, id)).isEqualTo(1);
    }

    // ---- MT-05 (AC2)

    @Test
    void aStoppedJobChangesNoEntitlement() {
        String id = manual(days(-1));
        entitlements.setEnabled(true);
        assertThat(entitlements.forBusiness(owner.businessId())).isEqualTo(Entitlements.unrestricted());
        assertThat(stored(id)).containsEntry("status", "ACTIVE").containsEntry("row_version", 0);
        assertThat(jobs()).isZero();
    }

    // ---- the pre-switch report (AC3, read-only portion)

    @Test
    void theReportListsExactlyTheQualifyingRows() throws Exception {
        Tenant missing = data.tenant("r-missing"), future = data.tenant("r-future"), own = data.tenant("r-owner"), trial = data.tenant("r-trial"), ended = data.tenant("r-ended");
        String past = manual(days(-1));
        String noEnd = row(missing.businessId(), "MANUAL", "ACTIVE", "MODEL_4", null, null, false, null);
        row(future.businessId(), "MANUAL", "ACTIVE", "MODEL_4", days(5), null, false, null);
        row(own.businessId(), "OWNER", "ACTIVE", "MODEL_2", days(-1), null, false, null);
        row(trial.businessId(), "TRIAL", "TRIAL", "MODEL_1", days(-1), null, false, null);
        row(ended.businessId(), "MANUAL", "EXPIRED", "MODEL_4", days(-5), null, false, days(-5));
        evaluator.setPeriodEndEnabled(false);
        String json = mockMvc.perform(get("/api/admin/subscriptions/period-end-report").header("Authorization", bearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andReturn().getResponse().getContentAsString();
        var items = mapper.readTree(json).get("items");
        Map<String, String> reasons = new java.util.HashMap<>();
        items.forEach(i -> reasons.put(i.get("subscriptionId").asString(), i.get("reason").asString()));
        assertThat(reasons).containsOnly(Map.entry(past, "PAST"), Map.entry(noEnd, "MISSING"));
        assertThat(items.get(0).get("businessName").asString()).isNotBlank();
    }

    @Test
    void theReportIsReadOnlyAndAdminOnly() throws Exception {
        String id = manual(days(-1));
        int auditsBefore = jdbc.queryForObject("select count(*) from admin_audit_event", Integer.class);
        mockMvc.perform(get("/api/admin/subscriptions/period-end-report").header("Authorization", bearer)).andExpect(status().isOk());
        assertThat(stored(id)).containsEntry("status", "ACTIVE").containsEntry("row_version", 0);
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event", Integer.class)).isEqualTo(auditsBefore);
        mockMvc.perform(get("/api/admin/subscriptions/period-end-report")).andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(401, 403));
        mockMvc.perform(get("/api/admin/subscriptions/period-end-report").param("size", "1000").header("Authorization", bearer)).andExpect(status().isBadRequest());
    }

    @Test
    void extendingThroughTheExistingAdminPathIsAuditedAndLeavesTheReport() throws Exception {
        String id = manual(days(-1));
        assertThat(extend(0, days(30))).isEqualTo(200);
        mockMvc.perform(get("/api/admin/subscriptions/period-end-report").header("Authorization", bearer)).andExpect(jsonPath("$.total").value(0));
        assertThat(jdbc.queryForObject("select count(*) from admin_audit_event where action = 'SUBSCRIPTION_UPDATED' and target_id = ?", Integer.class, id)).isEqualTo(1);
    }
}
