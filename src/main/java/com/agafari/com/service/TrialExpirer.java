package com.agafari.com.service;

import com.agafari.com.subscription.SubscriptionLifecycle;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.security.AdminAuditService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AGF E4-M5 (D-M5-13): persists the expiry of a trial whose period end has passed. Used by the daily job and "on touch" by
 * writes that a due trial would affect. The conditional update (still TRIAL, period end passed) and the restaurant lock make
 * every path idempotent against the others: a trial is expired, and audited, exactly once.
 */
@Component
public class TrialExpirer {

    public static final String EXPIRED_ACTION = "TRIAL_EXPIRED";

    private final BusinessSubscriptionRepository subscriptions;
    private final AdminAuditService audit;
    private final MonetizationLocks locks;
    private final MonetizationClock clock;
    private final TransactionTemplate transaction;
    private final SubscriptionLifecycle lifecycle;

    public TrialExpirer(BusinessSubscriptionRepository subscriptions, AdminAuditService audit, MonetizationLocks locks,
                        MonetizationClock clock, PlatformTransactionManager transactionManager, SubscriptionLifecycle lifecycle) {
        this.lifecycle = lifecycle;
        this.subscriptions = subscriptions;
        this.audit = audit;
        this.locks = locks;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Expires one due trial; the caller holds the restaurant lock inside a transaction. True if this call expired it. */
    boolean expire(String businessId, String subscriptionId, String how) {
        if (!lifecycle.expireTrialIfDue(businessId, subscriptionId, clock.now())) return false; // AGF SUB-R2-04
        audit.success(EXPIRED_ACTION, null, AdminAuditService.SYSTEM, "BUSINESS_SUBSCRIPTION", subscriptionId, "MODEL_1 TRIAL -> EXPIRED " + how);
        return true;
    }

    /**
     * Expire on touch, in its own transaction so the expiry stays persisted even if the request that touched it is then
     * refused. True if a trial of this restaurant was expired by this call.
     */
    public boolean expireDueOnTouch(String businessId) {
        Boolean expired = transaction.execute(status -> {
            locks.restaurant(businessId);
            return subscriptions.findCurrentByBusinessId(businessId)
                    .filter(s -> BusinessSubscription.TRIAL.equals(s.getStatus()) && !s.isEffectivelyCurrent(clock.now()))
                    .map(s -> expire(s.getBusinessId(), s.getId(), "on_touch")).orElse(false);
        });
        return Boolean.TRUE.equals(expired);
    }
}
