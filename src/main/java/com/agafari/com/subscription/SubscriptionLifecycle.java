package com.agafari.com.subscription;

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

    /** Persists the effective expiry of the current row before a new one is created. A no-op until SUB-R2-07. */
    public void materialize(String businessId) {
    }
}
