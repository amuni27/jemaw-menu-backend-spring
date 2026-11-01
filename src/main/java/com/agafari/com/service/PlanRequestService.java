package com.agafari.com.service;

import com.agafari.com.dto.request.PlanRequestCreateRequest;
import com.agafari.com.dto.request.PlanRequestDeclineRequest;
import com.agafari.com.dto.request.PlanRequestFulfilRequest;
import com.agafari.com.dto.request.PlanRequestWithdrawRequest;
import com.agafari.com.dto.response.OwnerPlanView;
import com.agafari.com.dto.response.PlanRequestPage;
import com.agafari.com.dto.response.PlanRequestResponse;
import com.agafari.com.dto.response.PlanRequestSummary;
import com.agafari.com.enums.MonetizationModel;
import com.agafari.com.enums.MonetizationSetting;
import com.agafari.com.exception.ConfigVersionConflictException;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.exception.ForbiddenException;
import com.agafari.com.exception.MonetizationNotConfiguredException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.exception.PlanNotEnabledException;
import com.agafari.com.exception.PlanRequestExistsException;
import com.agafari.com.exception.PlanRequestVersionConflictException;
import com.agafari.com.exception.SubscriptionExistsException;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.entity.MonetizationConfig;
import com.agafari.com.jpa.entity.PlanRequest;
import com.agafari.com.jpa.entity.SubscriptionPlan;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.jpa.repository.MonetizationConfigRepository;
import com.agafari.com.jpa.repository.PlanRequestRepository;
import com.agafari.com.jpa.repository.SubscriptionPlanRepository;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.security.LogMasking;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AGF E4-M4: owner plan requests. An owner's choice is a request, never a subscription and never an entitlement change; the
 * Super Admin fulfils it by recording the E4-M3 manual subscription for the requested plan (linked in the same
 * transaction) or declines it; the owner can withdraw it. Requests are possible only under Setting 2, for an enabled plan,
 * without a current subscription or another pending request. Nothing happens automatically.
 * <p>
 * Concurrency (proposal §16): every write takes the configuration lock (shared) and then the restaurant lock, so owner and
 * Super Admin actions on one restaurant are serialized (I1: never a current subscription and a pending request together)
 * and writes are made only against the configuration current at commit (I2).
 */
@Service
public class PlanRequestService {

    public static final String OWNER_REQUESTED = "OWNER_PLAN_REQUESTED";
    public static final String OWNER_WITHDRAWN = "OWNER_PLAN_REQUEST_WITHDRAWN";
    public static final String OWNER_REJECTED = "OWNER_PLAN_REQUEST_REJECTED";
    public static final String FULFILLED = "PLAN_REQUEST_FULFILLED";
    public static final String DECLINED = "PLAN_REQUEST_DECLINED";
    public static final String ADMIN_REJECTED = "PLAN_REQUEST_REJECTED";
    private static final String TARGET = "PLAN_REQUEST";
    private static final Set<String> REQUESTABLE = Set.of("MODEL_2", "MODEL_3", "MODEL_4");

    private final PlanRequestRepository requests;
    private final BusinessSubscriptionRepository subscriptions;
    private final MonetizationConfigRepository configs;
    private final SubscriptionPlanRepository plans;
    private final BusinessRepository businesses;
    private final UserRepository users;
    private final SubscriptionService subscriptionService;
    private final AdminAuditService audit;
    private final MonetizationLocks locks;
    private final TransactionTemplate transaction;
    private final Clock clock = Clock.systemUTC();
    private volatile boolean ownerPlansVisible;

    public PlanRequestService(PlanRequestRepository requests, BusinessSubscriptionRepository subscriptions, MonetizationConfigRepository configs,
                              SubscriptionPlanRepository plans, BusinessRepository businesses, UserRepository users,
                              SubscriptionService subscriptionService, AdminAuditService audit, MonetizationLocks locks,
                              PlatformTransactionManager transactionManager,
                              @Value("${app.monetization.owner-plans-visible:false}") boolean ownerPlansVisible) {
        this.requests = requests;
        this.subscriptions = subscriptions;
        this.configs = configs;
        this.plans = plans;
        this.businesses = businesses;
        this.users = users;
        this.subscriptionService = subscriptionService;
        this.audit = audit;
        this.locks = locks;
        this.transaction = new TransactionTemplate(transactionManager);
        this.ownerPlansVisible = ownerPlansVisible;
    }

    /** Tests only: the flag itself comes from configuration. */
    public void setOwnerPlansVisible(boolean visible) {
        this.ownerPlansVisible = visible;
    }

    // --------------------------------------------------------------------------------------------------- owner

    public OwnerPlanView ownerView(String businessId) {
        if (!ownerPlansVisible) return OwnerPlanView.hidden();
        MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
        if (config == null) return OwnerPlanView.hidden();
        boolean setting2 = config.getSetting() == MonetizationSetting.SETTING_2;
        List<OwnerPlanView.Plan> eligible = !setting2 ? List.of() : plans.findAllByOrderByCodeAsc().stream()
                .filter(p -> config.models().contains(MonetizationModel.valueOf(p.getModel())))
                .map(p -> new OwnerPlanView.Plan(p.getCode(), p.getModel(), p.getAdSource(), p.isPaid())).toList();
        OwnerPlanView.Subscription current = subscriptions.findCurrentByBusinessId(businessId)
                .map(s -> new OwnerPlanView.Subscription(s.getPlanCode(), s.getStatus(), s.getPeriodEnd(),
                        BusinessSubscription.MANUAL.equals(s.getSource()) ? SubscriptionService.MANUAL_LABEL : null, outside(config, s.getPlanCode())))
                .orElse(null);
        PlanRequestSummary pending = requests.findPendingByBusinessId(businessId).map(r -> summary(r, config)).orElse(null);
        return new OwnerPlanView(true, config.getSetting().name(), config.getVersion(), eligible, current, pending,
                setting2 && current == null && pending == null, false, true);
    }

    public PlanRequestResponse request(String businessId, PlanRequestCreateRequest request, String ownerId, String ownerEmail) {
        if (!ownerPlansVisible) throw new ForbiddenException("Plans aren't available yet");
        String actor = LogMasking.maskEmail(ownerEmail);
        Instant now = clock.instant();
        try {
            validateRequest(request);
            return transaction.execute(status -> {
                locks.configShared();
                locks.restaurant(businessId);
                MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElseThrow(MonetizationNotConfiguredException::new);
                if (!config.getVersion().equals(request.expectedConfigVersion())) throw new ConfigVersionConflictException();
                if (config.getSetting() != MonetizationSetting.SETTING_2 || !config.models().contains(MonetizationModel.valueOf(request.planCode()))) {
                    throw new FieldValidationException(Map.of("planCode", "This plan isn't available for your restaurant"));
                }
                if (subscriptions.findCurrentByBusinessId(businessId).isPresent()) throw new SubscriptionExistsException();
                if (requests.findPendingByBusinessId(businessId).isPresent()) throw new PlanRequestExistsException();
                PlanRequest saved = requests.saveAndFlush(PlanRequest.pending(businessId, request.planCode(), config.getVersion(), now, ownerId));
                audit.success(OWNER_REQUESTED, ownerId, actor, TARGET, saved.getId(), saved.getPlanCode() + " config=" + saved.getConfigVersion());
                return response(saved, config, false);
            });
        } catch (DataIntegrityViolationException e) {
            // a concurrent request won on the pending-request index; anything else is a real error
            if (requests.findPendingByBusinessId(businessId).isEmpty()) throw e;
            audit.failure(OWNER_REJECTED, ownerId, actor, "exists");
            throw new PlanRequestExistsException();
        } catch (RuntimeException e) {
            String reason = reason(e);
            if (reason != null) audit.failure(OWNER_REJECTED, ownerId, actor, reason);
            throw e;
        }
    }

    public PlanRequestResponse withdraw(String businessId, PlanRequestWithdrawRequest request, String ownerId, String ownerEmail) {
        if (!ownerPlansVisible) throw new ForbiddenException("Plans aren't available yet");
        String actor = LogMasking.maskEmail(ownerEmail);
        Instant now = clock.instant();
        try {
            Map<String, String> errors = new LinkedHashMap<>();
            if (!PlanRequest.WITHDRAWN.equals(request.status())) errors.put("status", "A request can only be withdrawn here");
            if (request.rowVersion() == null) errors.put("rowVersion", "Reload and try again");
            if (!errors.isEmpty()) throw new FieldValidationException(errors);
            return transaction.execute(status -> {
                locks.restaurant(businessId);
                PlanRequest pending = requests.findPendingByBusinessId(businessId).orElseThrow(() -> new NotFoundException("No pending plan request"));
                if (!pending.getRowVersion().equals(request.rowVersion())) throw new PlanRequestVersionConflictException();
                pending.resolve(PlanRequest.WITHDRAWN, now, ownerId);
                PlanRequest saved = requests.saveAndFlush(pending);
                audit.success(OWNER_WITHDRAWN, ownerId, actor, TARGET, saved.getId(), saved.getPlanCode() + " PENDING -> WITHDRAWN");
                return response(saved, configs.findTopByOrderByVersionDesc().orElse(null), false);
            });
        } catch (OptimisticLockingFailureException e) {
            audit.failure(OWNER_REJECTED, ownerId, actor, "version_conflict");
            throw new PlanRequestVersionConflictException();
        } catch (RuntimeException e) {
            String reason = reason(e);
            if (reason != null) audit.failure(OWNER_REJECTED, ownerId, actor, reason);
            throw e;
        }
    }

    // ---------------------------------------------------------------------------------------------- Super Admin

    public PlanRequestPage list(String status, int page, int size) {
        MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
        var result = requests.findByStatusOrderByRequestedAtDesc(status, PageRequest.of(page, size));
        return new PlanRequestPage(result.getContent().stream().map(r -> response(r, config, true)).toList(), page, size, result.getTotalElements());
    }

    public PlanRequestResponse fulfil(String requestId, PlanRequestFulfilRequest request, String adminId, String adminEmail) {
        String actor = LogMasking.maskEmail(adminEmail);
        Instant now = clock.instant();
        try {
            Map<String, String> errors = new LinkedHashMap<>();
            if (request.expectedConfigVersion() == null || request.expectedConfigVersion() < 0) errors.put("expectedConfigVersion", "Reload and try again");
            if (request.rowVersion() == null) errors.put("rowVersion", "Reload and try again");
            if (request.periodEnd() != null && !request.periodEnd().isAfter(now)) errors.put("periodEnd", "Choose a date in the future");
            if (!errors.isEmpty()) throw new FieldValidationException(errors);
            String businessId = requests.findById(requestId).map(PlanRequest::getBusinessId)
                    .orElseThrow(() -> new NotFoundException("Plan request not found"));
            return transaction.execute(status -> {
                locks.configShared();
                locks.restaurant(businessId);
                PlanRequest pending = requests.findById(requestId).orElseThrow(() -> new NotFoundException("Plan request not found"));
                if (!pending.isPending() || !pending.getRowVersion().equals(request.rowVersion())) throw new PlanRequestVersionConflictException();
                MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElseThrow(MonetizationNotConfiguredException::new);
                if (!config.getVersion().equals(request.expectedConfigVersion())) throw new ConfigVersionConflictException();
                if (!config.models().contains(MonetizationModel.valueOf(pending.getPlanCode()))) throw new PlanNotEnabledException();
                if (subscriptions.findCurrentByBusinessId(businessId).isPresent()) throw new SubscriptionExistsException();
                BusinessSubscription subscription = subscriptionService.recordWithinTransaction(businessId, pending.getPlanCode(),
                        config.getVersion(), request.periodEnd(), now, adminId, actor);
                pending.fulfil(subscription.getId(), now, adminId);
                PlanRequest saved = requests.saveAndFlush(pending);
                audit.success(FULFILLED, adminId, actor, TARGET, saved.getId(), saved.getPlanCode() + " config=" + config.getVersion()
                        + " subscription=" + subscription.getId() + " source=" + subscription.getSource());
                return response(saved, config, true);
            });
        } catch (OptimisticLockingFailureException e) {
            audit.failure(ADMIN_REJECTED, adminId, actor, "version_conflict");
            throw new PlanRequestVersionConflictException();
        } catch (DataIntegrityViolationException e) {
            audit.failure(ADMIN_REJECTED, adminId, actor, "subscription_exists"); // the M3 partial unique index
            throw new SubscriptionExistsException();
        } catch (RuntimeException e) {
            String reason = reason(e);
            if (reason != null) audit.failure(ADMIN_REJECTED, adminId, actor, reason);
            throw e;
        }
    }

    public PlanRequestResponse decline(String requestId, PlanRequestDeclineRequest request, String adminId, String adminEmail) {
        String actor = LogMasking.maskEmail(adminEmail);
        Instant now = clock.instant();
        try {
            if (request.rowVersion() == null) throw new FieldValidationException(Map.of("rowVersion", "Reload and try again"));
            String businessId = requests.findById(requestId).map(PlanRequest::getBusinessId)
                    .orElseThrow(() -> new NotFoundException("Plan request not found"));
            return transaction.execute(status -> {
                locks.restaurant(businessId);
                PlanRequest pending = requests.findById(requestId).orElseThrow(() -> new NotFoundException("Plan request not found"));
                if (!pending.isPending() || !pending.getRowVersion().equals(request.rowVersion())) throw new PlanRequestVersionConflictException();
                pending.resolve(PlanRequest.DECLINED, now, adminId);
                PlanRequest saved = requests.saveAndFlush(pending);
                audit.success(DECLINED, adminId, actor, TARGET, saved.getId(), saved.getPlanCode() + " PENDING -> DECLINED");
                return response(saved, configs.findTopByOrderByVersionDesc().orElse(null), true);
            });
        } catch (OptimisticLockingFailureException e) {
            audit.failure(ADMIN_REJECTED, adminId, actor, "version_conflict");
            throw new PlanRequestVersionConflictException();
        } catch (RuntimeException e) {
            String reason = reason(e);
            if (reason != null) audit.failure(ADMIN_REJECTED, adminId, actor, reason);
            throw e;
        }
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static void validateRequest(PlanRequestCreateRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.planCode() == null || request.planCode().isBlank()) {
            errors.put("planCode", "Choose a plan");
        } else if (SubscriptionPlan.APPROVED.stream().noneMatch(p -> p.getCode().equals(request.planCode()))) {
            errors.put("planCode", "Unknown plan");
        } else if (!REQUESTABLE.contains(request.planCode())) {
            errors.put("planCode", "This plan can't be requested");
        }
        if (request.expectedConfigVersion() == null || request.expectedConfigVersion() < 0) {
            errors.put("expectedConfigVersion", "Reload and try again");
        }
        if (!errors.isEmpty()) throw new FieldValidationException(errors);
    }

    /** The audit reason category for an expected rejection, or null for unexpected errors. */
    private static String reason(RuntimeException e) {
        if (e instanceof FieldValidationException) return "invalid";
        if (e instanceof NotFoundException) return "not_found";
        if (e instanceof MonetizationNotConfiguredException) return "not_configured";
        if (e instanceof ConfigVersionConflictException || e instanceof PlanRequestVersionConflictException) return "version_conflict";
        if (e instanceof PlanNotEnabledException) return "plan_not_enabled";
        if (e instanceof SubscriptionExistsException) return "subscription_exists";
        if (e instanceof PlanRequestExistsException) return "exists";
        return null;
    }

    private static boolean outside(MonetizationConfig config, String planCode) {
        return config != null && !config.models().contains(MonetizationModel.valueOf(planCode));
    }

    private static PlanRequestSummary summary(PlanRequest r, MonetizationConfig config) {
        return new PlanRequestSummary(r.getId(), r.getPlanCode(), r.getConfigVersion(), r.getRequestedAt(), r.getRowVersion(),
                r.isPending() && outside(config, r.getPlanCode()));
    }

    private PlanRequestResponse response(PlanRequest r, MonetizationConfig config, boolean forAdmin) {
        String businessName = forAdmin ? businesses.findById(r.getBusinessId()).map(b -> b.getName()).orElse(null) : null;
        String requestedBy = forAdmin ? users.findById(r.getRequestedByUserId()).map(u -> LogMasking.maskEmail(u.getEmail())).orElse("unknown") : null;
        return new PlanRequestResponse(r.getId(), r.getBusinessId(), businessName, r.getPlanCode(), r.getConfigVersion(), r.getStatus(),
                r.getRequestedAt(), requestedBy, r.getResolvedAt(), r.getSubscriptionId(), r.getRowVersion(),
                r.isPending() && outside(config, r.getPlanCode()));
    }
}
