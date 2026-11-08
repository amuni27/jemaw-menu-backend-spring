package com.agafari.com.service;

import com.agafari.com.subscription.Actor;
import com.agafari.com.subscription.SubscriptionLifecycle;
import com.agafari.com.dto.request.SubscriptionActivationRequest;
import com.agafari.com.dto.response.SubscriptionActivationResponse;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.exception.SelfServiceRefusedException;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.entity.MonetizationConfig;
import com.agafari.com.enums.MonetizationModel;
import com.agafari.com.enums.MonetizationSetting;
import com.agafari.com.jpa.entity.PlanRequest;
import com.agafari.com.jpa.entity.SubscriptionPlan;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.jpa.repository.MonetizationConfigRepository;
import com.agafari.com.jpa.repository.PlanRequestRepository;
import com.agafari.com.jpa.repository.SubscriptionPlanRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.security.LogMasking;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * AGF Self-Service Phase A (SS-1…SS-4): an owner starts a <b>free</b> plan (Free with Ads) themselves, without Super Admin
 * approval, only while {@code app.monetization.self-service-enabled} and the owner plan view are on. Every check is made
 * here, never trusted from the client: Setting 2 current, the configuration version the owner saw, the plan enabled and
 * free, no current subscription, and (SS-2) a verified email while verification is operational. A pending plan request is
 * withdrawn only when the owner names it with the version they saw (SS-3), in the same transaction as the activation.
 * Same lock order as every monetization write (configuration, then restaurant); the one-current-subscription index is the
 * last guard. A repeated click returns the same subscription. Paid plans are never activated here. Every outcome audited.
 */
@Service
public class SelfServiceActivationService {

    public static final String ACTIVATED = "OWNER_SUBSCRIPTION_ACTIVATED";
    public static final String REJECTED = "OWNER_ACTIVATION_REJECTED";
    private static final String TARGET = "BUSINESS_SUBSCRIPTION";

    private final PlanRequestService planRequests;
    private final BusinessSubscriptionRepository subscriptions;
    private final PlanRequestRepository requests;
    private final MonetizationConfigRepository configs;
    private final SubscriptionPlanRepository plans;
    private final MonetizationLocks locks;
    private final TrialExpirer trialExpirer;
    private final GrandfatheringService grandfathering;
    private final AdminAuditService audit;
    private final TransactionTemplate transaction;
    private final SubscriptionLifecycle lifecycle;
    private final Clock clock = Clock.systemUTC();

    public SelfServiceActivationService(PlanRequestService planRequests, BusinessSubscriptionRepository subscriptions, PlanRequestRepository requests,
                                        MonetizationConfigRepository configs, SubscriptionPlanRepository plans, MonetizationLocks locks,
                                        TrialExpirer trialExpirer, GrandfatheringService grandfathering, AdminAuditService audit,
                                        PlatformTransactionManager transactionManager, SubscriptionLifecycle lifecycle) {
        this.lifecycle = lifecycle;
        this.planRequests = planRequests;
        this.subscriptions = subscriptions;
        this.requests = requests;
        this.configs = configs;
        this.plans = plans;
        this.locks = locks;
        this.trialExpirer = trialExpirer;
        this.grandfathering = grandfathering;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public SubscriptionActivationResponse activate(String businessId, SubscriptionActivationRequest request, String ownerId, String ownerEmail) {
        String actor = LogMasking.maskEmail(ownerEmail);
        try {
            if (!planRequests.isOwnerPlansVisible() || !planRequests.isSelfServiceEnabled()) throw SelfServiceRefusedException.disabled();
            SubscriptionPlan plan = validate(request);
            if (planRequests.verificationRequired(businessId)) throw SelfServiceRefusedException.unverified();
            trialExpirer.expireDueOnTouch(businessId); // an effectively ended trial no longer counts as current
            grandfathering.expireDueOnTouch(businessId); // nor a grandfathered subscription past its approved end (flag on)
            try {
                return transaction.execute(status -> activateLocked(businessId, request, plan, ownerId, actor));
            } catch (DataIntegrityViolationException e) {
                // the one-current-subscription index: a concurrent activation of the same free plan is the same click
                return sameFreePlan(businessId, plan).orElseThrow(SelfServiceRefusedException::exists);
            }
        } catch (SelfServiceRefusedException e) {
            audit.failure(REJECTED, ownerId, actor, TARGET, businessId, e.auditReason());
            throw e;
        } catch (FieldValidationException e) {
            audit.failure(REJECTED, ownerId, actor, TARGET, businessId, "invalid");
            throw e;
        }
    }

    private SubscriptionActivationResponse activateLocked(String businessId, SubscriptionActivationRequest request, SubscriptionPlan plan,
                                                          String ownerId, String actor) {
        locks.configShared(); // no configuration change between these checks and the commit
        locks.restaurant(businessId); // serialized with admin records, plan requests, fulfilment, trial expiry and grandfathering
        MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElseThrow(SelfServiceRefusedException::notConfigured);
        if (config.getSetting() != MonetizationSetting.SETTING_2) throw SelfServiceRefusedException.setting1();
        if (!config.getVersion().equals(request.expectedConfigVersion())) throw SelfServiceRefusedException.versionConflict();
        if (!config.models().contains(MonetizationModel.valueOf(plan.getModel()))) throw SelfServiceRefusedException.notEnabled();
        if (plan.isPaid()) throw SelfServiceRefusedException.requiresPayment();

        Optional<BusinessSubscription> current = subscriptions.findCurrentByBusinessId(businessId);
        if (current.isPresent()) {
            BusinessSubscription s = current.get();
            if (isSameFreePlan(s, plan)) return response(s, false, null); // a repeated click
            throw SelfServiceRefusedException.exists();
        }

        Instant now = clock.instant();
        String withdrawn = null;
        Optional<PlanRequest> pending = requests.findPendingByBusinessId(businessId);
        if (pending.isPresent()) {
            PlanRequest r = pending.get();
            if (request.withdrawPendingRequestId() == null) throw SelfServiceRefusedException.pending(); // never withdrawn silently
            if (!r.getId().equals(request.withdrawPendingRequestId()) || !r.getRowVersion().equals(request.withdrawPendingRequestRowVersion())) {
                throw SelfServiceRefusedException.requestChanged();
            }
            r.resolve(PlanRequest.WITHDRAWN, now, ownerId);
            requests.saveAndFlush(r);
            audit.success(PlanRequestService.OWNER_WITHDRAWN, ownerId, actor, "PLAN_REQUEST", r.getId(),
                    r.getPlanCode() + " PENDING -> WITHDRAWN (withdrawn by owner to start Free with Ads)");
            withdrawn = r.getId();
        } else if (request.withdrawPendingRequestId() != null) {
            throw SelfServiceRefusedException.requestChanged(); // the confirmed request was resolved meanwhile
        }

        BusinessSubscription saved = lifecycle.startOwnerFree(businessId, plan.getCode(), config.getVersion(), now, new Actor(ownerId)); // AGF SUB-R2-02
        audit.success(ACTIVATED, ownerId, actor, TARGET, saved.getId(), saved.getPlanCode() + " config=" + config.getVersion() + " source=OWNER"
                + (withdrawn == null ? "" : " withdrawn request " + withdrawn));
        return response(saved, true, withdrawn);
    }

    private SubscriptionPlan validate(SubscriptionActivationRequest request) {
        SubscriptionPlan plan = request.planCode() == null ? null : plans.findById(request.planCode()).orElse(null);
        if (plan == null) throw new FieldValidationException(Map.of("planCode", "Choose a plan"));
        if (request.expectedConfigVersion() == null) throw new FieldValidationException(Map.of("expectedConfigVersion", "Reload and try again"));
        return plan;
    }

    private Optional<SubscriptionActivationResponse> sameFreePlan(String businessId, SubscriptionPlan plan) {
        return subscriptions.findCurrentByBusinessId(businessId).filter(s -> isSameFreePlan(s, plan)).map(s -> response(s, false, null));
    }

    private static boolean isSameFreePlan(BusinessSubscription s, SubscriptionPlan plan) {
        return BusinessSubscription.OWNER.equals(s.getSource()) && s.getPlanCode().equals(plan.getCode());
    }

    private static SubscriptionActivationResponse response(BusinessSubscription s, boolean created, String withdrawn) {
        return new SubscriptionActivationResponse(s.getId(), s.getPlanCode(), s.getStatus(), created, withdrawn);
    }
}
