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

    /** Persists the effective expiry of the current row before a new one is created. A no-op until SUB-R2-07. */
    public void materialize(String businessId) {
    }
}
