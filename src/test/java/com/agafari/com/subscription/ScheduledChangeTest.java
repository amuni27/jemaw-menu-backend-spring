package com.agafari.com.subscription;

import com.agafari.com.dto.response.ScheduledChangeResponse;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.exception.ScheduledChangeRefusedException;
import com.agafari.com.jobs.JobHandler;
import com.agafari.com.jobs.JobRunner;
import com.agafari.com.jpa.entity.User;
import com.agafari.com.service.Entitlements;
import com.agafari.com.service.EntitlementService;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.ScheduledChangeService;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agafari.com.support.MonetizationFixtures.config;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AGF SUB-R2-11: scheduled changes are intent only. PROVIDER rows are test fixtures: V20 refuses real ones. */
class ScheduledChangeTest extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ScheduledChangeService service;
    @Autowired private ScheduledChangeJob job;
    @Autowired private EntitlementService entitlements;
    @Autowired private EffectiveStateEvaluator evaluator;
    @Autowired private MonetizationClock clock;
    @Autowired private ObjectMapper mapper;
    @Autowired private ApplicationContext context;

    private final List<JobRunner> runners = new ArrayList<>();
    private User admin;
    private Tenant owner;
    private String ownerId;
    private Instant end;

    @BeforeEach
    void setUp() {
        admin = data.admin("sc-admin");
        owner = data.tenant("sc-owner");
        ownerId = owner.owner().getId();
        config(jdbc, 1, "SETTING_2", true, false, true, admin.getId());
        service.setBillingEnabled(true);
        end = days(5);
    }

    @AfterEach
    void reset() {
        runners.forEach(JobRunner::shutdown);
        service.setBillingEnabled(false);
        entitlements.setEnabled(false);
        evaluator.setPeriodEndEnabled(false);
        clock.reset();
    }

    private static Instant days(int n) {
        return Instant.now().plus(n, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    }

    private String row(String businessId, String source, String status, String plan, Instant periodEnd) {
        String id = UUID.randomUUID().toString();
        boolean provider = "PROVIDER".equals(source);
        if (provider) jdbc.execute("alter table business_subscription disable trigger business_subscription_refuse_provider");
        jdbc.update("insert into business_subscription (id, business_id, plan_code, status, config_version, period_end, source, created_at, created_by_user_id, updated_at, updated_by_user_id, row_version) "
                + "values (?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, 0)", id, businessId, plan, status, periodEnd == null ? null : Timestamp.from(periodEnd), source,
                Timestamp.from(days(-40)), admin.getId(), Timestamp.from(days(-40)), admin.getId());
        if (provider) jdbc.execute("alter table business_subscription enable trigger business_subscription_refuse_provider");
        return id;
    }

    private String manual() {
        return row(owner.businessId(), "MANUAL", "ACTIVE", "MODEL_4", end);
    }

    private String provider() {
        return row(owner.businessId(), "PROVIDER", "ACTIVE", "MODEL_4", end);
    }

    private ScheduledChangeResponse downgrade() {
        return service.scheduleDowngrade(owner.businessId(), "MODEL_2", ownerId, "o@example.test");
    }

    private ScheduledChangeResponse cancel() {
        return service.scheduleCancel(owner.businessId(), ownerId, "o@example.test");
    }

    private void afterEnd() {
        clock.setOffset(Duration.ofDays(6));
    }

    private Map<String, Object> sub(String id) {
        return jdbc.queryForMap("select status, ended_at, ended_reason, replaced_by_id, row_version from business_subscription where id = ?", id);
    }

    private String changeStatus(String id) {
        return jdbc.queryForObject("select status from scheduled_change where id = ?", String.class, id);
    }

    private int open() {
        return jdbc.queryForObject("select count(*) from scheduled_change where business_id = ? and status in ('PENDING', 'AWAITING_PAYMENT')", Integer.class, owner.businessId());
    }

    private int audits(String action, String target) {
        return jdbc.queryForObject("select count(*) from admin_audit_event where action = ? and target_id = ?", Integer.class, action, target);
    }

    private Map<String, Object> current() {
        return jdbc.queryForMap("select id, plan_code, status, source, created_at, created_by_user_id from business_subscription where business_id = ? and status <> 'EXPIRED'", owner.businessId());
    }

    private int subscriptions() {
        return jdbc.queryForObject("select count(*) from business_subscription where business_id = ?", Integer.class, owner.businessId());
    }

    private JobRunner runner(JobHandler<?> handler) {
        JobRunner r = new JobRunner(jdbc, List.of(handler), mapper, 4, Clock.systemUTC());
        runners.add(r);
        return r;
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

    // ---- scheduling

    @Test
    void aDowngradeIsRecordedForThePeriodEndAndAudited() {
        manual();
        ScheduledChangeResponse r = downgrade();
        assertThat(r.kind()).isEqualTo("DOWNGRADE");
        assertThat(r.targetPlanCode()).isEqualTo("MODEL_2");
        assertThat(r.effectiveAt()).isEqualTo(end);
        assertThat(r.status()).isEqualTo("PENDING");
        assertThat(audits("SCHEDULED_CHANGE_REQUESTED", r.id())).isEqualTo(1);
    }

    @Test
    void aCancelIsRecordedForThePeriodEnd() {
        provider();
        ScheduledChangeResponse r = cancel();
        assertThat(r.kind()).isEqualTo("CANCEL");
        assertThat(r.targetPlanCode()).isNull();
        assertThat(r.effectiveAt()).isEqualTo(end);
    }

    // ---- AC1 and NC-08

    @Test
    void aPendingChangeGrantsNothingBeforeItsTime() {
        String id = manual();
        downgrade();
        entitlements.setEnabled(true);
        Entitlements e = entitlements.forBusiness(owner.businessId());
        assertThat(e.planCode()).isEqualTo("MODEL_4");
        assertThat(e.adSource()).isEqualTo(Entitlements.AdSource.NONE);
        assertThat(sub(id)).containsEntry("status", "ACTIVE").containsEntry("row_version", 0);
        assertThat(subscriptions()).isEqualTo(1);
    }

    @Test
    void aPendingCancelEndsNothingBeforeItsTime() {
        provider();
        cancel();
        entitlements.setEnabled(true);
        assertThat(entitlements.forBusiness(owner.businessId()).planCode()).isEqualTo("MODEL_4");
    }

    // ---- read time

    @Test
    void afterItsTimeADowngradeIsTreatedAsAppliedBeforeTheJobRuns() {
        String id = manual();
        downgrade();
        entitlements.setEnabled(true);
        afterEnd();
        Entitlements e = entitlements.forBusiness(owner.businessId());
        assertThat(e.planCode()).isEqualTo("MODEL_2");
        assertThat(e.status()).isEqualTo("ACTIVE");
        assertThat(e.source()).isEqualTo("OWNER");
        assertThat(e.adSource()).isEqualTo(Entitlements.AdSource.AGAFARI);
        assertThat(sub(id)).containsEntry("status", "ACTIVE");
    }

    @Test
    void afterItsTimeACancelIsTreatedAsAppliedBeforeTheJobRuns() {
        provider();
        cancel();
        entitlements.setEnabled(true);
        afterEnd();
        assertThat(entitlements.forBusiness(owner.businessId())).isEqualTo(Entitlements.unrestricted());
    }

    // ---- AC2

    @Test
    void aSecondRequestSupersedesTheFirst() {
        provider();
        ScheduledChangeResponse first = downgrade();
        ScheduledChangeResponse second = cancel();
        assertThat(changeStatus(first.id())).isEqualTo("SUPERSEDED");
        assertThat(changeStatus(second.id())).isEqualTo("PENDING");
        assertThat(open()).isEqualTo(1);
        assertThat(audits("SCHEDULED_CHANGE_SUPERSEDED", first.id())).isEqualTo(1);
    }

    @Test
    void simultaneousRequestsLeaveOnePending() throws Exception {
        manual();
        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> all = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                all.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return downgrade();
                }));
            }
            for (Future<?> f : all) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(open()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from scheduled_change where business_id = ? and status = 'SUPERSEDED'", Integer.class, owner.businessId())).isEqualTo(7);
    }

    // ---- AC3

    @Test
    void aCancelThenResumeLeavesNoPendingRow() {
        provider();
        ScheduledChangeResponse r = cancel();
        service.cancelPending(owner.businessId(), ownerId, "o@example.test");
        assertThat(open()).isZero();
        assertThat(changeStatus(r.id())).isEqualTo("CANCELLED");
        assertThat(audits("SCHEDULED_CHANGE_CANCELLED", r.id())).isEqualTo(1);
    }

    @Test
    void aResumedChangeIsNeverApplied() {
        String id = manual();
        ScheduledChangeResponse r = downgrade();
        service.cancelPending(owner.businessId(), ownerId, "o@example.test");
        afterEnd();
        assertThat(service.apply(r.id())).isFalse();
        assertThat(sub(id)).containsEntry("status", "ACTIVE");
    }

    @Test
    void resumingWithNothingPendingIsRefused() {
        manual();
        assertThatThrownBy(() -> service.cancelPending(owner.businessId(), ownerId, "o@example.test")).isInstanceOf(ScheduledChangeRefusedException.class);
    }

    @Test
    void aChangeAlreadyInEffectCantBeResumedOrSuperseded() {
        manual();
        ScheduledChangeResponse r = downgrade();
        afterEnd();
        assertThatThrownBy(() -> service.cancelPending(owner.businessId(), ownerId, "o@example.test")).isInstanceOf(ScheduledChangeRefusedException.class);
        assertThat(changeStatus(r.id())).isEqualTo("PENDING");
    }

    // ---- AC4 and application

    @Test
    void aFreeDowngradeCreatesAnOwnerRowAtItsTimeAttributedToTheRequester() {
        String id = manual();
        ScheduledChangeResponse r = downgrade();
        afterEnd();
        assertThat(service.apply(r.id())).isTrue();
        Map<String, Object> next = current();
        assertThat(next).containsEntry("plan_code", "MODEL_2").containsEntry("status", "ACTIVE").containsEntry("source", "OWNER").containsEntry("created_by_user_id", ownerId);
        assertThat(((Timestamp) next.get("created_at")).toInstant()).isEqualTo(end);
        Map<String, Object> old = sub(id);
        assertThat(old).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "CHANGED").containsEntry("replaced_by_id", next.get("id")).containsEntry("row_version", 1);
        assertThat(((Timestamp) old.get("ended_at")).toInstant()).isEqualTo(end);
        assertThat(changeStatus(r.id())).isEqualTo("APPLIED");
        assertThat(audits("SCHEDULED_CHANGE_APPLIED", r.id())).isEqualTo(1);
    }

    @Test
    void aCancelEndsTheRowAtItsTime() {
        String id = provider();
        ScheduledChangeResponse r = cancel();
        afterEnd();
        assertThat(service.apply(r.id())).isTrue();
        Map<String, Object> old = sub(id);
        assertThat(old).containsEntry("status", "EXPIRED").containsEntry("ended_reason", "CANCELLED");
        assertThat(((Timestamp) old.get("ended_at")).toInstant()).isEqualTo(end);
        assertThat(subscriptions()).isEqualTo(1);
    }

    @Test
    void aChangeIsNotAppliedBeforeItsTime() {
        String id = manual();
        ScheduledChangeResponse r = downgrade();
        assertThat(service.apply(r.id())).isFalse();
        assertThat(sub(id)).containsEntry("status", "ACTIVE");
        assertThat(changeStatus(r.id())).isEqualTo("PENDING");
    }

    @Test
    void applyingTwiceChangesOnce() {
        manual();
        ScheduledChangeResponse r = downgrade();
        afterEnd();
        assertThat(service.apply(r.id())).isTrue();
        assertThat(service.apply(r.id())).isFalse();
        assertThat(subscriptions()).isEqualTo(2);
        assertThat(audits("SCHEDULED_CHANGE_APPLIED", r.id())).isEqualTo(1);
    }

    @Test
    void concurrentApplicationsChangeOnce() throws Exception {
        manual();
        ScheduledChangeResponse r = downgrade();
        afterEnd();
        CyclicBarrier barrier = new CyclicBarrier(6);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        int applied = 0;
        try {
            List<Future<Boolean>> all = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                all.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return service.apply(r.id());
                }));
            }
            for (Future<Boolean> f : all) if (f.get(30, TimeUnit.SECONDS)) applied++;
        } finally {
            pool.shutdownNow();
        }
        assertThat(applied).isEqualTo(1);
        assertThat(subscriptions()).isEqualTo(2);
        assertThat(audits("SCHEDULED_CHANGE_APPLIED", r.id())).isEqualTo(1);
    }

    @Test
    void aChangeFromARowNoLongerCurrentFailsWithoutWriting() {
        String id = manual();
        ScheduledChangeResponse r = downgrade();
        jdbc.update("update business_subscription set status = 'EXPIRED', ended_at = now() where id = ?", id);
        afterEnd();
        assertThat(service.apply(r.id())).isFalse();
        assertThat(changeStatus(r.id())).isEqualTo("FAILED");
        assertThat(audits("SCHEDULED_CHANGE_FAILED", r.id())).isEqualTo(1);
        assertThat(subscriptions()).isEqualTo(1);
    }

    @Test
    void aChangeFromAReplacedRowLeavesTheNewRowAlone() {
        String id = manual();
        ScheduledChangeResponse r = downgrade();
        jdbc.update("update business_subscription set status = 'EXPIRED', ended_at = now() where id = ?", id);
        String replacement = row(owner.businessId(), "MANUAL", "ACTIVE", "MODEL_4", days(30));
        afterEnd();
        assertThat(service.apply(r.id())).isFalse();
        assertThat(changeStatus(r.id())).isEqualTo("FAILED");
        assertThat(sub(replacement)).containsEntry("status", "ACTIVE").containsEntry("row_version", 0);
        assertThat(subscriptions()).isEqualTo(2);
    }

    // ---- the job

    @Test
    void theRunnerAppliesADueChangeOnce() throws Exception {
        String id = manual();
        ScheduledChangeResponse r = downgrade();
        assertThat(job.enqueueDue()).isZero();
        afterEnd();
        assertThat(job.enqueueDue()).isEqualTo(1);
        job.enqueueDue();
        assertThat(jdbc.queryForObject("select count(*) from job where type = ?", Integer.class, ScheduledChangeJob.TYPE)).isEqualTo(1);
        drain(runner(job).pollOnce());
        assertThat(changeStatus(r.id())).isEqualTo("APPLIED");
        assertThat(sub(id)).containsEntry("status", "EXPIRED");
    }

    @Test
    void aWorkerKilledBeforeTheWriteIsRecovered() throws Exception {
        manual();
        ScheduledChangeResponse r = downgrade();
        afterEnd();
        job.enqueueDue();
        AtomicInteger runs = new AtomicInteger();
        JobRunner w = runner(new JobHandler<ScheduledChangeJob.Payload>() {
            @Override public String type() { return ScheduledChangeJob.TYPE; }
            @Override public Class<ScheduledChangeJob.Payload> payloadType() { return ScheduledChangeJob.Payload.class; }
            @Override public void handle(ScheduledChangeJob.Payload p) {
                if (runs.incrementAndGet() == 1) throw new Error("killed");
                job.handle(p);
            }
        });
        drain(w.pollOnce());
        assertThat(changeStatus(r.id())).isEqualTo("PENDING");
        jdbc.update("update job set lease_until = now() - interval '1 second' where status = 'RUNNING'");
        drain(w.pollOnce());
        assertThat(changeStatus(r.id())).isEqualTo("APPLIED");
        assertThat(subscriptions()).isEqualTo(2);
    }

    @Test
    void theCadenceExistsOnlyWithTheJobFlagOn() {
        assertThat(context.getBeanNamesForType(ScheduledChangeCadence.class)).isEmpty();
        ApplicationContextRunner contexts = new ApplicationContextRunner().withBean(ScheduledChangeJob.class, () -> mock(ScheduledChangeJob.class)).withUserConfiguration(ScheduledChangeCadence.class);
        contexts.run(c -> assertThat(c).doesNotHaveBean(ScheduledChangeCadence.class));
        contexts.withPropertyValues("app.jobs.enabled=true").run(c -> assertThat(c).hasSingleBean(ScheduledChangeCadence.class));
    }

    // ---- against period end

    @Test
    void periodEndPersistenceLeavesADueChangeToBeApplied() {
        String id = manual();
        ScheduledChangeResponse r = downgrade();
        evaluator.setPeriodEndEnabled(true);
        afterEnd();
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(s -> {
            locks.configShared();
            locks.restaurant(owner.businessId());
            lifecycle.materialize(owner.businessId());
        });
        assertThat(sub(id)).containsEntry("status", "ACTIVE");
        assertThat(service.apply(r.id())).isTrue();
        assertThat(sub(id)).containsEntry("ended_reason", "CHANGED");
        assertThat(current()).containsEntry("plan_code", "MODEL_2");
    }

    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired private com.agafari.com.service.MonetizationLocks locks;
    @Autowired private SubscriptionLifecycle lifecycle;

    // ---- refusals

    @Test
    void onlyFreeWithAdsIsATarget() {
        manual();
        assertThatThrownBy(() -> service.scheduleDowngrade(owner.businessId(), "MODEL_3", ownerId, "o@example.test")).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.scheduleDowngrade(owner.businessId(), "MODEL_4", ownerId, "o@example.test")).as("a paid plan the configuration enables").isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> service.scheduleDowngrade(owner.businessId(), "NONSENSE", ownerId, "o@example.test")).isInstanceOf(FieldValidationException.class);
        assertThat(open()).isZero();
    }

    @Test
    void aTargetTheConfigurationDoesNotEnableIsRefused() {
        jdbc.update("insert into monetization_config (version, setting, model_2_enabled, model_3_enabled, model_4_enabled, effective_from, created_at, created_by_user_id) values (2, 'SETTING_2', false, false, true, now(), now(), ?)", admin.getId());
        manual();
        assertThatThrownBy(this::downgrade).isInstanceOf(FieldValidationException.class);
    }

    @Test
    void aRowAlreadyOnTheTargetIsRefused() {
        row(owner.businessId(), "OWNER", "ACTIVE", "MODEL_2", end);
        assertThatThrownBy(this::downgrade).isInstanceOf(ScheduledChangeRefusedException.class);
    }

    @Test
    void aTrialIsRefused() {
        row(owner.businessId(), "TRIAL", "TRIAL", "MODEL_1", end);
        assertThatThrownBy(this::downgrade).isInstanceOf(ScheduledChangeRefusedException.class);
        assertThatThrownBy(this::cancel).isInstanceOf(ScheduledChangeRefusedException.class);
    }

    @Test
    void aRowWithoutAPeriodEndIsRefused() {
        row(owner.businessId(), "MANUAL", "ACTIVE", "MODEL_4", null);
        assertThatThrownBy(this::downgrade).isInstanceOf(ScheduledChangeRefusedException.class);
    }

    @Test
    void aRowPastItsEndIsRefused() {
        row(owner.businessId(), "MANUAL", "ACTIVE", "MODEL_4", days(-1));
        assertThatThrownBy(this::downgrade).isInstanceOf(ScheduledChangeRefusedException.class);
    }

    @Test
    void noSubscriptionIsRefused() {
        assertThatThrownBy(this::downgrade).isInstanceOf(ScheduledChangeRefusedException.class);
    }

    @Test
    void aManualRowCantBeCancelledBecauseTheTransitionTableDoesNotAllowIt() {
        manual();
        assertThatThrownBy(this::cancel).isInstanceOf(ScheduledChangeRefusedException.class);
    }

    // ---- API and flag

    private String ownerBearer() {
        return data.bearer(owner);
    }

    @Test
    void theApiIsClosedWithBillingOff() throws Exception {
        manual();
        service.setBillingEnabled(false);
        String bearer = ownerBearer();
        mockMvc.perform(post("/api/billing/subscription/change").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content("{\"planCode\":\"MODEL_2\"}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/billing/subscription/cancel").header("Authorization", bearer)).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/billing/subscription/scheduled-change").header("Authorization", bearer)).andExpect(status().isForbidden());
        assertThat(open()).isZero();
    }

    @Test
    void theApiSchedulesAndResumes() throws Exception {
        manual();
        String bearer = ownerBearer();
        mockMvc.perform(post("/api/billing/subscription/change").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content("{\"planCode\":\"MODEL_2\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.kind").value("DOWNGRADE"));
        assertThat(open()).isEqualTo(1);
        mockMvc.perform(delete("/api/billing/subscription/scheduled-change").header("Authorization", bearer)).andExpect(status().isNoContent());
        assertThat(open()).isZero();
        mockMvc.perform(delete("/api/billing/subscription/scheduled-change").header("Authorization", bearer)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SCHEDULED_CHANGE_REFUSED"));
    }
}
