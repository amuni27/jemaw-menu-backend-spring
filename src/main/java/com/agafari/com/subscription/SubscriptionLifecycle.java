package com.agafari.com.subscription;

import com.agafari.com.exception.ProviderPeriodManagedByBillingException;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.service.MonetizationClock;
import com.agafari.com.service.MonetizationLocks;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * AGF SUB-R2-02: the start of the single writer of {@code business_subscription} (Backend Detailed Design Rev. 2 §2.1).
 * Each command runs in the caller's transaction, requires the configuration and restaurant locks, checks the transition
 * table and writes the row. Validation, messages and audit stay with the calling services. Today this covers
 * Free with Ads self-service and manual records; the remaining writers move here in later stories.
 */
@Service
public class SubscriptionLifecycle {

    static final String PERIOD_ENDED = "SUBSCRIPTION_PERIOD_ENDED";
    static final String PAST_DUE = "SUBSCRIPTION_PAST_DUE";

    private final BusinessSubscriptionRepository subscriptions;
    private final MonetizationLocks locks;
    private final EffectiveStateEvaluator evaluator;
    private final MonetizationClock clock;
    private final AdminAuditService audit;

    public SubscriptionLifecycle(BusinessSubscriptionRepository subscriptions, MonetizationLocks locks, EffectiveStateEvaluator evaluator,
                                 MonetizationClock clock, AdminAuditService audit) {
        this.subscriptions = subscriptions;
        this.locks = locks;
        this.evaluator = evaluator;
        this.clock = clock;
        this.audit = audit;
    }

    public BusinessSubscription startOwnerFree(String businessId, String planCode, int configVersion, Instant now, Actor owner) {
        locks.assertHeld(businessId);
        materialize(businessId);
        TransitionRules.require(null, LifecycleStatus.ACTIVE, TransitionReason.OWNER_FREE, SubscriptionSource.OWNER);
        return subscriptions.saveAndFlush(BusinessSubscription.owner(businessId, planCode, configVersion, now, owner.userId()));
    }

    public BusinessSubscription recordManual(String businessId, ManualRecord record, Actor admin) {
        locks.assertHeld(businessId);
        materialize(businessId);
        TransitionRules.require(null, LifecycleStatus.ACTIVE, TransitionReason.ADMIN_RECORD, SubscriptionSource.MANUAL);
        return subscriptions.saveAndFlush(BusinessSubscription.manual(businessId, record.planCode(), record.configVersion(), record.periodEnd(),
                record.now(), admin.userId()));
    }

    /** R-4: a PROVIDER billing period moves only with verified financial evidence, never by a Super Admin edit. */
    public void requireAdminPeriodEditable(BusinessSubscription current) {
        if (SubscriptionSource.of(current.getSource()) == SubscriptionSource.PROVIDER) throw new ProviderPeriodManagedByBillingException();
    }

    /**
     * AGF SUB-R2-03: one Super Admin change to the current row, saved once (the row version moves by one even when a
     * single request changes the period and ends the subscription). The caller holds the locks and has validated the request.
     */
    public BusinessSubscription applyAdminChange(BusinessSubscription current, AdminPeriodChange change, Actor admin) {
        locks.assertHeld(current.getBusinessId());
        LifecycleStatus status = LifecycleStatus.of(current.getStatus());
        SubscriptionSource source = SubscriptionSource.of(current.getSource());
        if (change.periodEnd() != null) {
            requireAdminPeriodEditable(current);
            TransitionRules.require(status, status, TransitionReason.ADMIN_PERIOD_CHANGE, source);
        }
        if (change.end()) TransitionRules.require(status, LifecycleStatus.EXPIRED, TransitionReason.ADMIN, source);
        if (change.clearTransitionDate()) current.changeTransitionDate(null, change.now(), admin.userId());
        if (change.periodEnd() != null) current.changePeriodEnd(change.periodEnd(), change.now(), admin.userId());
        if (change.end()) {
            current.end(change.now(), admin.userId());
            current.recordEnding(TransitionReason.ADMIN.name(), null);
        }
        return subscriptions.saveAndFlush(current);
    }

    /** AGF SUB-R2-03: sets ({@code transitionDate} not null) or clears the transition date of a current row. */
    public BusinessSubscription changeTransitionDate(BusinessSubscription current, Instant transitionDate, Instant now, Actor admin) {
        locks.assertHeld(current.getBusinessId());
        current.changeTransitionDate(transitionDate, now, admin.userId());
        return subscriptions.saveAndFlush(current);
    }

    /** AGF SUB-R2-04: creates a Model 1 trial. */
    public BusinessSubscription startTrial(String businessId, int configVersion, Instant now, Instant end, Actor createdBy) {
        locks.assertHeld(businessId);
        materialize(businessId);
        TransitionRules.require(null, LifecycleStatus.TRIAL, TransitionReason.REGISTRATION_TRIAL, SubscriptionSource.TRIAL);
        return subscriptions.saveAndFlush(BusinessSubscription.trial(businessId, configVersion, now, end, createdBy.userId()));
    }

    /**
     * AGF SUB-R2-04: trial conversion, end + new row in the caller's transaction. The trial is flushed first so the
     * one-current index accepts the successor. {@code trialEndedAt} is the earlier of now and the trial's own end.
     */
    public BusinessSubscription replace(BusinessSubscription trial, Instant trialEndedAt, ManualRecord successor, Actor admin) {
        locks.assertHeld(trial.getBusinessId());
        TransitionRules.requireReplacement(LifecycleStatus.of(trial.getStatus()), SubscriptionSource.of(trial.getSource()), TransitionReason.CONVERTED,
                TransitionReason.ADMIN_RECORD, SubscriptionSource.MANUAL);
        // The link must go in with the end (an ended row is frozen), so the successor's id is known before it is saved.
        BusinessSubscription next = BusinessSubscription.manual(trial.getBusinessId(), successor.planCode(), successor.configVersion(), successor.periodEnd(),
                successor.now(), admin.userId());
        trial.endTrial(trialEndedAt, successor.now(), admin.userId());
        trial.recordEnding(TransitionReason.CONVERTED.name(), next.getId());
        subscriptions.saveAndFlush(trial);
        materialize(trial.getBusinessId());
        TransitionRules.require(null, LifecycleStatus.ACTIVE, TransitionReason.ADMIN_RECORD, SubscriptionSource.MANUAL);
        return subscriptions.saveAndFlush(next);
    }

    /** AGF SUB-R2-04: the existing idempotent trial expiry; true if this call expired it. Needs only the restaurant lock, as before. */
    public boolean expireTrialIfDue(String businessId, String subscriptionId, Instant now) {
        locks.assertRestaurantHeld(businessId);
        TransitionRules.require(LifecycleStatus.TRIAL, LifecycleStatus.EXPIRED, TransitionReason.TRIAL_END, SubscriptionSource.TRIAL);
        return subscriptions.expireIfDue(subscriptionId, now) == 1;
    }

    /** AGF SUB-R2-04: the existing conditional end of a grandfathered subscription; true if this call ended it. */
    public boolean endGrandfathered(BusinessSubscription s, Instant endedAt, Instant now) {
        locks.assertHeld(s.getBusinessId());
        TransitionRules.require(LifecycleStatus.of(s.getStatus()), LifecycleStatus.EXPIRED, TransitionReason.GRANDFATHERED_END, SubscriptionSource.of(s.getSource()));
        return subscriptions.endGrandfathered(s.getId(), endedAt, now, s.getRowVersion()) == 1;
    }

    /** AGF SUB-R2-11: a scheduled cancellation takes effect at {@code effectiveAt}. */
    public BusinessSubscription applyScheduledCancel(BusinessSubscription current, Instant effectiveAt, Instant now) {
        locks.assertHeld(current.getBusinessId());
        TransitionRules.require(LifecycleStatus.of(current.getStatus()), LifecycleStatus.EXPIRED, TransitionReason.CANCELLED, SubscriptionSource.of(current.getSource()));
        current.endAtEffectiveTime(effectiveAt, TransitionReason.CANCELLED.name(), now, AdminAuditService.SYSTEM);
        return subscriptions.saveAndFlush(current);
    }

    /** AGF SUB-R2-11: a scheduled move to a free plan: the row ends and the owner's row starts at {@code effectiveAt}. */
    public BusinessSubscription applyScheduledDowngrade(BusinessSubscription current, String planCode, int configVersion, Instant effectiveAt, Instant now, String ownerId) {
        locks.assertHeld(current.getBusinessId());
        TransitionRules.requireReplacement(LifecycleStatus.of(current.getStatus()), SubscriptionSource.of(current.getSource()), TransitionReason.CHANGED,
                TransitionReason.OWNER_FREE, SubscriptionSource.OWNER);
        BusinessSubscription next = BusinessSubscription.owner(current.getBusinessId(), planCode, configVersion, effectiveAt, ownerId);
        current.endAtEffectiveTime(effectiveAt, TransitionReason.CHANGED.name(), now, AdminAuditService.SYSTEM);
        current.recordEnding(TransitionReason.CHANGED.name(), next.getId());
        subscriptions.saveAndFlush(current);
        return subscriptions.saveAndFlush(next);
    }

    /**
     * AGF SUB-R2-07 (DD §3.3): persists the effective state of the current row, so the one-current index never blocks a
     * legitimate successor. Does nothing with {@code period-end-enabled} off. Trials and grandfathering keep their own
     * expiry paths and audit rows ({@code TrialExpirer}, {@code GrandfatheringService}).
     */
    public void materialize(String businessId) {
        if (!evaluator.isPeriodEndEnabled()) return;
        locks.assertHeld(businessId);
        BusinessSubscription s = subscriptions.findCurrentByBusinessId(businessId).orElse(null);
        if (s == null || s.isTrial()) return;
        Instant now = clock.now();
        if (subscriptions.findDueScheduledChange(s.getId(), now).isPresent()) return; // AGF SUB-R2-11: the owner's change decides how this row ends
        EffectiveView view = evaluator.view(s, false, now);
        LifecycleStatus stored = LifecycleStatus.of(s.getStatus());
        SubscriptionSource source = SubscriptionSource.of(s.getSource());
        boolean ended = view.reasonCode().equals("PERIOD_END_PASSED") || view.reasonCode().equals("GRACE_ENDED");
        if (ended) {
            TransitionReason reason = stored == LifecycleStatus.GRANDFATHERED ? TransitionReason.GRANDFATHERED_END
                    : source == SubscriptionSource.PROVIDER ? TransitionReason.PAYMENT_FAILED : TransitionReason.PERIOD_END;
            if (source == SubscriptionSource.PROVIDER && stored == LifecycleStatus.ACTIVE) {
                TransitionRules.require(LifecycleStatus.ACTIVE, LifecycleStatus.PAST_DUE, TransitionReason.RENEWAL_FAILED, source); // grace passed unpersisted
                TransitionRules.require(LifecycleStatus.PAST_DUE, LifecycleStatus.EXPIRED, reason, source);
            } else {
                TransitionRules.require(stored, LifecycleStatus.EXPIRED, reason, source);
            }
            s.endAtEffectiveTime(view.graceUntil() != null ? view.graceUntil() : view.periodEnd(), reason.name(), now, AdminAuditService.SYSTEM);
            subscriptions.saveAndFlush(s);
            audit.success(PERIOD_ENDED, null, AdminAuditService.SYSTEM, "BUSINESS_SUBSCRIPTION", s.getId(), stored + " -> EXPIRED reason=" + reason);
        } else if (view.reasonCode().equals("IN_GRACE") && stored == LifecycleStatus.ACTIVE) {
            TransitionRules.require(LifecycleStatus.ACTIVE, LifecycleStatus.PAST_DUE, TransitionReason.RENEWAL_FAILED, source);
            s.markPastDue(view.periodEnd(), now, AdminAuditService.SYSTEM);
            subscriptions.saveAndFlush(s);
            audit.success(PAST_DUE, null, AdminAuditService.SYSTEM, "BUSINESS_SUBSCRIPTION", s.getId(), "ACTIVE -> PAST_DUE");
        }
    }
}
