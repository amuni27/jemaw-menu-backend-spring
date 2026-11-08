package com.agafari.com.subscription;

import com.agafari.com.exception.ProviderPeriodManagedByBillingException;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
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

    private final BusinessSubscriptionRepository subscriptions;
    private final MonetizationLocks locks;

    public SubscriptionLifecycle(BusinessSubscriptionRepository subscriptions, MonetizationLocks locks) {
        this.subscriptions = subscriptions;
        this.locks = locks;
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
        if (change.end()) current.end(change.now(), admin.userId());
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
        trial.endTrial(trialEndedAt, successor.now(), admin.userId());
        subscriptions.saveAndFlush(trial);
        return recordManual(trial.getBusinessId(), successor, admin);
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

    /** Persists the effective expiry of the current row before a new one is created. A no-op until SUB-R2-07. */
    public void materialize(String businessId) {
    }
}
