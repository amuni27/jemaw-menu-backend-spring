package com.agafari.com.service;

import com.agafari.com.enums.MonetizationModel;
import com.agafari.com.security.LogMasking;
import com.agafari.com.config.BillingFlags;
import com.agafari.com.dto.response.ScheduledChangeResponse;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.exception.MonetizationNotConfiguredException;
import com.agafari.com.exception.ScheduledChangeRefusedException;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.entity.MonetizationConfig;
import com.agafari.com.jpa.entity.ScheduledChange;
import com.agafari.com.jpa.entity.SubscriptionPlan;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.jpa.repository.MonetizationConfigRepository;
import com.agafari.com.jpa.repository.ScheduledChangeRepository;
import com.agafari.com.jpa.repository.SubscriptionPlanRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.subscription.EffectiveStateEvaluator;
import com.agafari.com.subscription.LifecycleStatus;
import com.agafari.com.subscription.ScheduledChangeRules;
import com.agafari.com.subscription.SubscriptionLifecycle;
import com.agafari.com.subscription.SubscriptionSource;
import com.agafari.com.subscription.TransitionReason;
import com.agafari.com.subscription.TransitionRules;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AGF SUB-R2-11 (TA §4.4): records an owner's cancellation or downgrade to Free with Ads, at the end of the current
 * period. Intent only: nothing changes before {@code effective_at}. Paid targets belong to a later story.
 */
@Slf4j
@Service
public class ScheduledChangeService {

    static final String REQUESTED = "SCHEDULED_CHANGE_REQUESTED";
    static final String SUPERSEDED = "SCHEDULED_CHANGE_SUPERSEDED";
    static final String CANCELLED = "SCHEDULED_CHANGE_CANCELLED";
    static final String APPLIED = "SCHEDULED_CHANGE_APPLIED";
    static final String INVALIDATED = "SCHEDULED_CHANGE_INVALIDATED";
    static final String FAILED = "SCHEDULED_CHANGE_FAILED";
    static final String REJECTED = "SCHEDULED_CHANGE_REJECTED";
    private static final String TARGET = "SCHEDULED_CHANGE";
    private static final String DOWNGRADE = "DOWNGRADE";
    private static final String CANCEL = "CANCEL";

    private final JdbcTemplate jdbc;
    private final ScheduledChangeRepository changes;
    private final BusinessSubscriptionRepository subscriptions;
    private final MonetizationConfigRepository configs;
    private final SubscriptionPlanRepository plans;
    private final SubscriptionLifecycle lifecycle;
    private final MonetizationLocks locks;
    private final AdminAuditService audit;
    private final EffectiveStateEvaluator evaluator;
    private final MonetizationClock clock;
    private final TransactionTemplate transaction;
    private volatile boolean billingEnabled;

    public ScheduledChangeService(JdbcTemplate jdbc, ScheduledChangeRepository changes, BusinessSubscriptionRepository subscriptions, MonetizationConfigRepository configs,
                                  SubscriptionPlanRepository plans, SubscriptionLifecycle lifecycle, MonetizationLocks locks, AdminAuditService audit,
                                  EffectiveStateEvaluator evaluator, MonetizationClock clock, PlatformTransactionManager transactions,
                                  BillingFlags flags) {
        this.jdbc = jdbc;
        this.changes = changes;
        this.subscriptions = subscriptions;
        this.configs = configs;
        this.plans = plans;
        this.lifecycle = lifecycle;
        this.locks = locks;
        this.audit = audit;
        this.evaluator = evaluator;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactions);
        this.billingEnabled = flags.billingEnabledInAnyMarket();
    }

    public boolean isBillingEnabled() {
        return billingEnabled;
    }

    /** Tests only: the flag itself comes from configuration. */
    public void setBillingEnabled(boolean enabled) {
        this.billingEnabled = enabled;
    }

    public ScheduledChangeResponse scheduleDowngrade(String businessId, String planCode, String ownerId, String email) {
        SubscriptionPlan plan = planCode == null ? null : plans.findById(planCode).orElse(null);
        if (plan == null || plan.isPaid()) throw new FieldValidationException(Map.of("planCode", "Only Free with Ads can be scheduled"));
        return schedule(businessId, DOWNGRADE, plan, ownerId, email);
    }

    public ScheduledChangeResponse scheduleCancel(String businessId, String ownerId, String email) {
        return schedule(businessId, CANCEL, null, ownerId, email);
    }

    private ScheduledChangeResponse schedule(String businessId, String kind, SubscriptionPlan target, String ownerId, String email) {
        String actor = LogMasking.maskEmail(email);
        try {
            return transaction.execute(status -> {
                locks.configShared();
                locks.restaurant(businessId);
                Instant now = clock.now();
                if (target != null) {
                    MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
                    if (config == null || !config.models().contains(MonetizationModel.valueOf(target.getModel()))) {
                        throw new FieldValidationException(Map.of("planCode", "This plan isn't enabled by the current configuration"));
                    }
                }
                BusinessSubscription current = subscriptions.findCurrentByBusinessId(businessId).orElseThrow(() -> new ScheduledChangeRefusedException("There is no current subscription to change"));
                requireSchedulable(current, kind, target, now);
                ScheduledChange open = changes.findOpenByBusinessId(businessId).orElse(null);
                if (open != null) {
                    requireNotInEffect(open, now);
                    jdbc.update("update scheduled_change set status = 'SUPERSEDED' where id = ? and status in ('PENDING', 'AWAITING_PAYMENT')", open.getId());
                    audit.success(SUPERSEDED, ownerId, actor, TARGET, open.getId(), open.getKind());
                }
                String id = UUID.randomUUID().toString();
                Instant effectiveAt = current.getPeriodEnd();
                String targetCode = target == null ? null : target.getCode();
                jdbc.update("insert into scheduled_change (id, business_id, from_subscription_id, kind, target_plan_code, effective_at, status) values (?, ?, ?, ?, ?, ?, 'PENDING')",
                        id, businessId, current.getId(), kind, targetCode, Timestamp.from(effectiveAt));
                audit.success(REQUESTED, ownerId, actor, TARGET, id, kind + " target=" + targetCode + " effectiveAt=" + effectiveAt);
                return new ScheduledChangeResponse(id, kind, targetCode, effectiveAt, "PENDING");
            });
        } catch (ScheduledChangeRefusedException e) {
            audit.failure(REJECTED, ownerId, actor, TARGET, businessId, kind);
            throw e;
        }
    }

    private void requireSchedulable(BusinessSubscription current, String kind, SubscriptionPlan target, Instant now) {
        LifecycleStatus status = LifecycleStatus.of(current.getStatus());
        TransitionReason reason = CANCEL.equals(kind) ? TransitionReason.CANCELLED : TransitionReason.CHANGED;
        if (status != LifecycleStatus.ACTIVE || !TransitionRules.isAllowed(status, LifecycleStatus.EXPIRED, reason, SubscriptionSource.of(current.getSource()))) {
            throw new ScheduledChangeRefusedException("This subscription can't take this change");
        }
        if (target != null && target.getCode().equals(current.getPlanCode())) throw new ScheduledChangeRefusedException("This subscription is already on that plan");
        if (current.getPeriodEnd() == null || !current.getPeriodEnd().isAfter(now) || !evaluator.view(current, false, now).entitledToPlan()) {
            throw new ScheduledChangeRefusedException("This subscription has no future period end to change at");
        }
    }

    private static void requireNotInEffect(ScheduledChange change, Instant now) {
        if (!change.getEffectiveAt().isAfter(now)) throw new ScheduledChangeRefusedException("The scheduled change is already in effect");
    }

    /** Resume: the pending change is cancelled. Too late once it is in effect. */
    public void cancelPending(String businessId, String ownerId, String email) {
        String actor = LogMasking.maskEmail(email);
        try {
            transaction.executeWithoutResult(status -> {
                locks.configShared();
                locks.restaurant(businessId);
                ScheduledChange open = changes.findOpenByBusinessId(businessId).orElseThrow(() -> new ScheduledChangeRefusedException("There is no scheduled change to cancel"));
                requireNotInEffect(open, clock.now());
                jdbc.update("update scheduled_change set status = 'CANCELLED' where id = ? and status in ('PENDING', 'AWAITING_PAYMENT')", open.getId());
                audit.success(CANCELLED, ownerId, actor, TARGET, open.getId(), open.getKind());
            });
        } catch (ScheduledChangeRefusedException e) {
            audit.failure(REJECTED, ownerId, actor, TARGET, businessId, "cancel_pending");
            throw e;
        }
    }

    /** Applies a due change once; false if there is nothing to apply. The row is read after the locks are taken. */
    public boolean apply(String changeId) {
        List<String> business = jdbc.queryForList("select business_id from scheduled_change where id = ?", String.class, changeId);
        if (business.isEmpty()) return false;
        String businessId = business.get(0);
        return Boolean.TRUE.equals(transaction.execute(status -> {
            locks.configShared();
            locks.restaurant(businessId);
            Map<String, Object> change = jdbc.queryForMap("select from_subscription_id, kind, target_plan_code, effective_at, status from scheduled_change where id = ?", changeId);
            Instant now = clock.now();
            Instant effectiveAt = ((Timestamp) change.get("effective_at")).toInstant();
            if (!"PENDING".equals(change.get("status")) || now.isBefore(effectiveAt)) return false;
            BusinessSubscription current = subscriptions.findCurrentByBusinessId(businessId).orElse(null);
            String kind = (String) change.get("kind");
            TransitionReason reason = CANCEL.equals(kind) ? TransitionReason.CANCELLED : TransitionReason.CHANGED;
            if (current == null || !current.getId().equals(change.get("from_subscription_id"))
                    || !TransitionRules.isAllowed(LifecycleStatus.of(current.getStatus()), LifecycleStatus.EXPIRED, reason, SubscriptionSource.of(current.getSource()))) {
                finish(changeId, "FAILED", FAILED, kind + " reason=row_changed");
                return false;
            }
            MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
            String targetPlan = (String) change.get("target_plan_code");
            if (!ScheduledChangeRules.targetEnabled(targetPlan, config)) {
                finish(changeId, "FAILED", FAILED, kind + " reason=target_plan_disabled target=" + targetPlan);
                return false;
            }
            if (CANCEL.equals(kind)) {
                lifecycle.applyScheduledCancel(current, effectiveAt, now);
            } else {
                String requester = jdbc.queryForObject("select actor_user_id from admin_audit_event where action = ? and target_id = ?", String.class, REQUESTED, changeId);
                if (config == null) throw new MonetizationNotConfiguredException();
                lifecycle.applyScheduledDowngrade(current, targetPlan, config.getVersion(), effectiveAt, now, requester);
            }
            finish(changeId, "APPLIED", APPLIED, kind);
            return true;
        }));
    }

    private void finish(String changeId, String status, String action, String detail) {
        jdbc.update("update scheduled_change set status = ? where id = ? and status = 'PENDING'", status, changeId);
        audit.success(action, null, AdminAuditService.SYSTEM, TARGET, changeId, detail);
    }

    /**
     * An admin change of {@code period_end} ends the row's pending change, in the caller's transaction and under its locks.
     * The change is not moved: the owner consented to the old date and can submit a fresh request.
     */
    public void invalidateForPeriodChange(String businessId, String subscriptionId, Instant before, Instant after, String adminId, String actor) {
        locks.assertHeld(businessId);
        for (Map<String, Object> change : jdbc.queryForList("select id, kind from scheduled_change where from_subscription_id = ? and status = 'PENDING'", subscriptionId)) {
            String id = (String) change.get("id");
            // By id and still PENDING: a row that changed after the select is left alone and isn't reported as invalidated
            if (jdbc.update("update scheduled_change set status = 'CANCELLED' where id = ? and status = 'PENDING'", id) != 1) {
                log.warn("event=scheduled_change.invalidate_skipped id={}", id);
                continue;
            }
            audit.success(INVALIDATED, adminId, actor, TARGET, id, change.get("kind") + " reason=period_end_changed periodEnd " + before + " -> " + after);
        }
    }
}
