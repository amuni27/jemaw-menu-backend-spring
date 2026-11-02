package com.agafari.com.service;

import com.agafari.com.dto.request.TrialConvertRequest;
import com.agafari.com.dto.request.TrialStartRequest;
import com.agafari.com.dto.response.SubscriptionResponse;
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
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.jpa.repository.MonetizationConfigRepository;
import com.agafari.com.jpa.repository.PlanRequestRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.security.LogMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AGF E4-M5: Model 1 trials (approved E4-Q2/Q4, D-M5-1…13).
 * <ul>
 *   <li>Start: after the registration commits, in its own transaction, when trials are enabled and Setting 1 is current
 *   (D-M5-3; a failure is audited as TRIAL_START_FAILED and never fails the registration), or by the Super Admin for one
 *   eligible restaurant (D-M5-8). At most one trial per restaurant, ever.</li>
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
    private volatile boolean enabled;

    public TrialService(BusinessSubscriptionRepository subscriptions, MonetizationConfigRepository configs, PlanRequestRepository planRequests,
                        BusinessRepository businesses, SubscriptionService subscriptionService, TrialExpirer expirer, AdminAuditService audit,
                        MonetizationLocks locks, MonetizationClock clock, PlatformTransactionManager transactionManager,
                        @Value("${app.monetization.trials-enabled:false}") boolean enabled,
                        @Value("${app.monetization.trial-duration:P30D}") Duration duration) {
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
     * Called by registration inside its transaction, after the restaurant row exists (D-M5-3, restored by the PO on
     * 2026-11-02). With trials disabled it does nothing (no lock, no query, no callback). Otherwise the trial starts after
     * the registration commits, in its own transaction: a registration that rolls back gets no trial, and a trial failure
     * never fails the registration (the trial rolls back and TRIAL_START_FAILED is recorded against the restaurant).
     */
    public void startAtRegistration(String businessId) {
        if (!enabled) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            startForNewRestaurant(businessId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                startForNewRestaurant(businessId);
            }
        });
    }

    /** Never throws: the registration has already committed and its response must not depend on the trial. */
    private void startForNewRestaurant(String businessId) {
        try {
            newTransaction.executeWithoutResult(status -> {
                locks.configShared();
                locks.restaurant(businessId);
                MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
                if (config == null || config.getSetting() != MonetizationSetting.SETTING_1) return; // eligibility at start time
                if (subscriptions.existsByBusinessIdAndSource(businessId, BusinessSubscription.TRIAL)
                        || subscriptions.findCurrentByBusinessId(businessId).isPresent()) return;
                insertTrial(businessId, config, AdminAuditService.SYSTEM, null, AdminAuditService.SYSTEM);
            });
        } catch (RuntimeException e) {
            log.warn("event=trial.start_failed businessId={} errorClass={}", businessId, e.getClass().getSimpleName());
            try {
                audit.failure(START_FAILED, null, AdminAuditService.SYSTEM, BUSINESS, businessId, "unexpected_error");
            } catch (RuntimeException auditError) {
                log.error("event=trial.start_failed.audit_failed businessId={} errorClass={}", businessId, auditError.getClass().getSimpleName());
            }
        }
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
            return transaction.execute(status -> {
                locks.configShared();
                locks.restaurant(businessId);
                MonetizationConfig config = currentSetting1(request.expectedConfigVersion());
                if (subscriptions.findCurrentByBusinessId(businessId).isPresent()) throw new SubscriptionExistsException();
                if (planRequests.findPendingByBusinessId(businessId).isPresent()) throw new PlanRequestPendingException();
                if (subscriptions.existsByBusinessIdAndSource(businessId, BusinessSubscription.TRIAL)) throw new TrialAlreadyUsedException();
                BusinessSubscription trial = insertTrial(businessId, config, adminId, adminId, actor);
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

    private BusinessSubscription insertTrial(String businessId, MonetizationConfig config, String createdBy, String actorId, String actorLabel) {
        Instant now = clock.now();
        BusinessSubscription trial = subscriptions.saveAndFlush(BusinessSubscription.trial(businessId, config.getVersion(), now, now.plus(duration), createdBy));
        audit.success(STARTED, actorId, actorLabel, TARGET, trial.getId(), "MODEL_1 TRIAL config=" + config.getVersion() + " periodEnd=" + trial.getPeriodEnd());
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
