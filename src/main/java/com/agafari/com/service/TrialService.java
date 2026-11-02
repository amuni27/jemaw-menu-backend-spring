package com.agafari.com.service;

import com.agafari.com.dto.request.TrialConvertRequest;
import com.agafari.com.dto.request.TrialStartRequest;
import com.agafari.com.dto.response.SubscriptionResponse;
import com.agafari.com.dto.response.TrialIntentOverview;
import com.agafari.com.dto.response.TrialIntentSummary;
import com.agafari.com.enums.MonetizationSetting;
import com.agafari.com.exception.ConfigVersionConflictException;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.exception.ForbiddenException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.exception.PlanNotEnabledException;
import com.agafari.com.exception.PlanRequestPendingException;
import com.agafari.com.exception.SubscriptionExistsException;
import com.agafari.com.exception.SubscriptionVersionConflictException;
import com.agafari.com.exception.TrialAlreadyUsedException;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.entity.MonetizationConfig;
import com.agafari.com.jpa.entity.TrialIntent;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.jpa.repository.MonetizationConfigRepository;
import com.agafari.com.jpa.repository.PlanRequestRepository;
import com.agafari.com.jpa.repository.TrialIntentRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.security.LogMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AGF E4-M5: Model 1 trials (approved E4-Q2/Q4, D-M5-1…13).
 * <ul>
 *   <li>Start: an eligible registration (trials enabled, Setting 1 current) commits a durable intent; the trial is created
 *   after the commit in its own transaction, or by the daily retry after a failure or a process stop (D-M5-3 and the
 *   recovery correction; failures are audited as TRIAL_START_FAILED and never fail the registration). Or by the Super
 *   Admin for one eligible restaurant (D-M5-8). At most one trial per restaurant, ever.</li>
 *   <li>Expiry: the daily job (one run at a time) and expire-on-touch, both through {@link TrialExpirer}. Expiry changes
 *   nothing for owners, QR menus or guests (status and in-app notice only).</li>
 *   <li>Convert: one transaction ends the trial (kept, frozen) and records a MANUAL Model 1 subscription, "no payment
 *   verified" (D-M5-7).</li>
 * </ul>
 */
@Slf4j
@Service
public class TrialService {

    public static final long TRIAL_JOB_LOCK_KEY = 4_400_000_002L;
    public static final String STARTED = "TRIAL_STARTED";
    public static final String START_FAILED = "TRIAL_START_FAILED";
    public static final String INTENT_RESOLVED = "TRIAL_INTENT_RESOLVED";
    private static final int OVERVIEW_ITEMS = 100;
    public static final String CONVERTED = "TRIAL_CONVERTED";
    public static final String REJECTED = "TRIAL_CHANGE_REJECTED";
    private static final String TARGET = "BUSINESS_SUBSCRIPTION";
    private static final String BUSINESS = "BUSINESS";

    private final BusinessSubscriptionRepository subscriptions;
    private final MonetizationConfigRepository configs;
    private final PlanRequestRepository planRequests;
    private final BusinessRepository businesses;
    private final SubscriptionService subscriptionService;
    private final TrialExpirer expirer;
    private final AdminAuditService audit;
    private final MonetizationLocks locks;
    private final MonetizationClock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate newTransaction;
    private final Duration duration;
    private final TrialIntentRepository intents;
    private final GrandfatheringService grandfathering;
    private volatile boolean enabled;
    private volatile boolean afterCommitEnabled = true;
    private volatile int recoveryBatchSize;

    public TrialService(BusinessSubscriptionRepository subscriptions, MonetizationConfigRepository configs, PlanRequestRepository planRequests,
                        BusinessRepository businesses, SubscriptionService subscriptionService, TrialExpirer expirer, AdminAuditService audit,
                        MonetizationLocks locks, MonetizationClock clock, PlatformTransactionManager transactionManager,
                        @Value("${app.monetization.trials-enabled:false}") boolean enabled,
                        @Value("${app.monetization.trial-duration:P30D}") Duration duration, TrialIntentRepository intents,
                        @Value("${app.monetization.trial-recovery-batch:100}") int recoveryBatchSize, GrandfatheringService grandfathering) {
        this.grandfathering = grandfathering;
        this.intents = intents;
        this.recoveryBatchSize = recoveryBatchSize;
        this.subscriptions = subscriptions;
        this.configs = configs;
        this.planRequests = planRequests;
        this.businesses = businesses;
        this.subscriptionService = subscriptionService;
        this.expirer = expirer;
        this.audit = audit;
        this.locks = locks;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); // runs after the caller's commit
        this.duration = duration;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Tests only: the flag itself comes from configuration. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Called by registration inside its transaction, after the restaurant row exists (D-M5-3 and the recovery correction,
     * 2026-11-02). With trials disabled it does nothing (no lock, no query, no callback). Otherwise, under the configuration
     * lock, an eligible registration (Setting 1 current) commits a durable PENDING intent with it; the trial itself is
     * created after the commit in its own transaction, or later by the daily retry. A registration that rolls back leaves
     * no intent and no trial; a trial failure never fails the registration.
     */
    public void startAtRegistration(String businessId) {
        if (!enabled) return;
        locks.configShared();
        MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
        if (config == null || config.getSetting() != MonetizationSetting.SETTING_1) return;
        intents.saveAndFlush(TrialIntent.pending(businessId, config.getVersion(), clock.now()));
        if (!afterCommitEnabled) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            fulfil(businessId, TrialIntent.AFTER_COMMIT);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                fulfil(businessId, TrialIntent.AFTER_COMMIT);
            }
        });
    }

    /** Tests only: simulates a process that stops right after the registration commits (the after-commit step never runs). */
    public void setAfterCommitEnabled(boolean afterCommitEnabled) {
        this.afterCommitEnabled = afterCommitEnabled;
    }

    /** Tests only: the batch size comes from configuration. */
    public void setRecoveryBatchSize(int recoveryBatchSize) {
        this.recoveryBatchSize = recoveryBatchSize;
    }

    /**
     * The daily retry (run by the expiry job): pending intents, never attempted first, then least recently attempted, each
     * in its own transaction. -1 if another instance is running; 0 while trials are disabled or Setting 1 isn't current
     * (intents stay pending); otherwise the number of intents resolved.
     */
    public int runRecovery() {
        if (!enabled) return 0;
        Integer resolved = transaction.execute(status -> {
            if (!locks.tryTrialJob(TRIAL_JOB_LOCK_KEY)) return -1;
            if (!setting1(configs.findTopByOrderByVersionDesc().orElse(null))) return 0;
            int count = 0;
            for (String businessId : intents.findRetryBatch(recoveryBatchSize)) {
                if (fulfil(businessId, TrialIntent.RETRY)) count++;
            }
            return count;
        });
        return resolved == null ? 0 : resolved;
    }

    public TrialIntentOverview intentOverview() {
        List<TrialIntent> pending = intents.findPending(PageRequest.of(0, OVERVIEW_ITEMS));
        Map<String, String> names = new HashMap<>();
        businesses.findAllById(pending.stream().map(TrialIntent::getBusinessId).toList()).forEach(b -> names.put(b.getId(), b.getName()));
        return new TrialIntentOverview(intents.countPending(), intents.countPendingWithFailures(TrialIntentSummary.NEEDS_ATTENTION_AFTER),
                pending.stream().map(i -> TrialIntentSummary.of(i, names.get(i.getBusinessId()))).toList());
    }

    /**
     * Creates the trial for a pending intent, in its own transaction, under the configuration and restaurant locks (the
     * same order as every other writer). Already resolved: nothing. Setting 1 not current: stays pending (no Model 1 trial
     * under Setting 2). A pending plan request: deferred. An existing trial or a current subscription resolves the intent
     * without a new trial. Never throws: a failure rolls the attempt back and is recorded. True if the intent was resolved.
     */
    private boolean fulfil(String businessId, String path) {
        try {
            Boolean resolved = newTransaction.execute(status -> {
                locks.configShared();
                locks.restaurant(businessId);
                TrialIntent intent = intents.findById(businessId).filter(TrialIntent::isPending).orElse(null);
                if (intent == null) return false;
                MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
                if (!setting1(config)) return false;
                Instant now = clock.now();
                if (planRequests.findPendingByBusinessId(businessId).isPresent()) {
                    intents.defer(businessId, now);
                    return false;
                }
                BusinessSubscription existingTrial = subscriptions.findFirstByBusinessIdAndSourceOrderByCreatedAtDesc(businessId, BusinessSubscription.TRIAL).orElse(null);
                if (existingTrial != null) return resolve(businessId, TrialIntent.STARTED, TrialIntent.EXISTING_TRIAL, existingTrial.getId(), now);
                BusinessSubscription current = subscriptions.findCurrentByBusinessId(businessId).orElse(null);
                if (current != null) return resolve(businessId, TrialIntent.NOT_STARTED, TrialIntent.SUBSCRIPTION_EXISTS, current.getId(), now);
                BusinessSubscription trial = insertTrial(businessId, config, AdminAuditService.SYSTEM, null, AdminAuditService.SYSTEM,
                        " path=" + path + " intentConfig=" + intent.getConfigVersion());
                if (intents.resolve(businessId, TrialIntent.STARTED, path, trial.getId(), now) != 1) throw new IllegalStateException("intent changed");
                return true;
            });
            return Boolean.TRUE.equals(resolved);
        } catch (RuntimeException e) {
            recordFailure(businessId, e);
            return false;
        }
    }

    private boolean resolve(String businessId, String status, String resolution, String subscriptionId, Instant now) {
        if (intents.resolve(businessId, status, resolution, subscriptionId, now) != 1) throw new IllegalStateException("intent changed");
        audit.success(INTENT_RESOLVED, null, AdminAuditService.SYSTEM, BUSINESS, businessId, status + "/" + resolution + " subscription=" + subscriptionId);
        log.info("event=trial.intent_resolved businessId={} status={} resolution={}", businessId, status, resolution);
        return true;
    }

    /** The attempt and its audit row, each in its own transaction; the intent stays pending for the next retry. */
    private void recordFailure(String businessId, RuntimeException e) {
        String error = e.getClass().getSimpleName();
        log.warn("event=trial.start_failed businessId={} errorClass={}", businessId, error);
        try {
            newTransaction.executeWithoutResult(status -> intents.recordFailure(businessId, clock.now(), error.length() > 64 ? error.substring(0, 64) : error));
        } catch (RuntimeException recordError) {
            log.error("event=trial.start_failed.record_failed businessId={} errorClass={}", businessId, recordError.getClass().getSimpleName());
        }
        try {
            audit.failure(START_FAILED, null, AdminAuditService.SYSTEM, BUSINESS, businessId, "unexpected_error");
        } catch (RuntimeException auditError) {
            log.error("event=trial.start_failed.audit_failed businessId={} errorClass={}", businessId, auditError.getClass().getSimpleName());
        }
    }

    private static boolean setting1(MonetizationConfig config) {
        return config != null && config.getSetting() == MonetizationSetting.SETTING_1;
    }

    /** The daily job: expires due trials. -1 if another instance is running, 0 when disabled or nothing is due. */
    public int runExpiry() {
        if (!enabled) return 0;
        Integer expired = transaction.execute(status -> {
            if (!locks.tryTrialJob(TRIAL_JOB_LOCK_KEY)) return -1;
            int count = 0;
            for (BusinessSubscription due : subscriptions.findDueTrials(clock.now())) {
                String id = due.getId();
                locks.restaurant(due.getBusinessId());
                if (expirer.expire(id, "job")) count++;
            }
            return count;
        });
        return expired == null ? 0 : expired;
    }

    public SubscriptionResponse start(String businessId, TrialStartRequest request, String adminId, String adminEmail) {
        if (!enabled) throw new ForbiddenException("Trials aren't enabled");
        String actor = LogMasking.maskEmail(adminEmail);
        try {
            if (request.expectedConfigVersion() == null) throw new FieldValidationException(Map.of("expectedConfigVersion", "Reload and try again"));
            if (!businesses.existsById(businessId)) throw new NotFoundException("Restaurant not found");
            expirer.expireDueOnTouch(businessId);
            grandfathering.expireDueOnTouch(businessId); // E4-M6 (flag on): a grandfathered subscription past its end no longer blocks
            return transaction.execute(status -> {
                locks.configShared();
                locks.restaurant(businessId);
                MonetizationConfig config = currentSetting1(request.expectedConfigVersion());
                if (subscriptions.findCurrentByBusinessId(businessId).isPresent()) throw new SubscriptionExistsException();
                if (planRequests.findPendingByBusinessId(businessId).isPresent()) throw new PlanRequestPendingException();
                if (subscriptions.existsByBusinessIdAndSource(businessId, BusinessSubscription.TRIAL)) throw new TrialAlreadyUsedException();
                BusinessSubscription trial = insertTrial(businessId, config, adminId, adminId, actor, "");
                intents.resolve(businessId, TrialIntent.STARTED, TrialIntent.MANUAL, trial.getId(), clock.now()); // a pending intent, if any
                return subscriptionService.toResponse(trial, config);
            });
        } catch (RuntimeException e) {
            String reason = reason(e);
            if (reason != null) audit.failure(REJECTED, adminId, actor, reason);
            throw e;
        }
    }

    public SubscriptionResponse convert(String businessId, TrialConvertRequest request, String adminId, String adminEmail) {
        String actor = LogMasking.maskEmail(adminEmail);
        Instant now = clock.now();
        try {
            Map<String, String> errors = new LinkedHashMap<>();
            if (request.expectedConfigVersion() == null) errors.put("expectedConfigVersion", "Reload and try again");
            if (request.rowVersion() == null) errors.put("rowVersion", "Reload and try again");
            if (request.periodEnd() != null && !request.periodEnd().isAfter(now)) errors.put("periodEnd", "Choose a date in the future");
            if (!errors.isEmpty()) throw new FieldValidationException(errors);
            return transaction.execute(status -> {
                locks.configShared();
                locks.restaurant(businessId);
                BusinessSubscription trial = subscriptions.findCurrentByBusinessId(businessId)
                        .filter(s -> s.isTrial() && BusinessSubscription.TRIAL.equals(s.getStatus()) && s.getRowVersion().equals(request.rowVersion()))
                        .orElseThrow(SubscriptionVersionConflictException::new);
                MonetizationConfig config = currentSetting1(request.expectedConfigVersion());
                Instant end = trial.getPeriodEnd().isBefore(now) ? trial.getPeriodEnd() : now; // an effectively ended trial keeps its real end
                trial.endTrial(end, now, adminId);
                subscriptions.saveAndFlush(trial);
                BusinessSubscription manual = subscriptionService.recordWithinTransaction(businessId, "MODEL_1", config.getVersion(),
                        request.periodEnd(), now, adminId, actor);
                audit.success(CONVERTED, adminId, actor, TARGET, trial.getId(), "trial=" + trial.getId() + " -> subscription=" + manual.getId()
                        + " source=" + manual.getSource() + " config=" + config.getVersion());
                return subscriptionService.toResponse(manual, config);
            });
        } catch (OptimisticLockingFailureException e) {
            audit.failure(REJECTED, adminId, actor, "version_conflict");
            throw new SubscriptionVersionConflictException();
        } catch (RuntimeException e) {
            String reason = reason(e);
            if (reason != null) audit.failure(REJECTED, adminId, actor, reason);
            throw e;
        }
    }

    private MonetizationConfig currentSetting1(int expectedConfigVersion) {
        MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElseThrow(ConfigVersionConflictException::new);
        if (!config.getVersion().equals(expectedConfigVersion)) throw new ConfigVersionConflictException();
        if (config.getSetting() != MonetizationSetting.SETTING_1) throw new PlanNotEnabledException();
        return config;
    }

    private BusinessSubscription insertTrial(String businessId, MonetizationConfig config, String createdBy, String actorId, String actorLabel,
                                             String detail) {
        Instant now = clock.now();
        BusinessSubscription trial = subscriptions.saveAndFlush(BusinessSubscription.trial(businessId, config.getVersion(), now, now.plus(duration), createdBy));
        audit.success(STARTED, actorId, actorLabel, TARGET, trial.getId(), "MODEL_1 TRIAL config=" + config.getVersion() + " periodEnd=" + trial.getPeriodEnd() + detail);
        log.info("event=trial.started businessId={} subscriptionId={} by={}", businessId, trial.getId(), actorId == null ? "system" : "admin");
        return trial;
    }

    private static String reason(RuntimeException e) {
        if (e instanceof FieldValidationException) return "invalid";
        if (e instanceof NotFoundException) return "not_found";
        if (e instanceof ConfigVersionConflictException || e instanceof SubscriptionVersionConflictException) return "version_conflict";
        if (e instanceof PlanNotEnabledException) return "plan_not_enabled";
        if (e instanceof SubscriptionExistsException) return "subscription_exists";
        if (e instanceof PlanRequestPendingException) return "request_pending";
        if (e instanceof TrialAlreadyUsedException) return "trial_used";
        return null;
    }
}
