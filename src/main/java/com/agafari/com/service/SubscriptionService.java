package com.agafari.com.service;

import com.agafari.com.dto.request.SubscriptionCreateRequest;
import com.agafari.com.dto.request.SubscriptionUpdateRequest;
import com.agafari.com.dto.response.AdminBusinessPage;
import com.agafari.com.dto.response.BusinessSubscriptionView;
import com.agafari.com.dto.response.SubscriptionPlanResponse;
import com.agafari.com.dto.response.SubscriptionResponse;
import com.agafari.com.enums.MonetizationModel;
import com.agafari.com.exception.ConfigVersionConflictException;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.exception.MonetizationNotConfiguredException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.exception.SubscriptionExistsException;
import com.agafari.com.exception.SubscriptionVersionConflictException;
import com.agafari.com.jpa.entity.Business;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.entity.MonetizationConfig;
import com.agafari.com.jpa.entity.SubscriptionPlan;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.jpa.repository.MonetizationConfigRepository;
import com.agafari.com.jpa.repository.SubscriptionPlanRepository;
import com.agafari.com.jpa.repository.UserRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.security.LogMasking;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AGF E4-M3: manual subscription records kept by the Super Admin (E4-Q7: records only, no payment processed or verified).
 * A record can be created only for a restaurant without a current subscription and only for a plan the current
 * monetization configuration enables; it stores that configuration version. It can then be ended or get a new period
 * end. Plans never change (D-M3-10); ended subscriptions stay as frozen history (D-M3-11). Nothing here runs on a
 * schedule or changes a subscription automatically (no trials, expiry, renewal or grandfathering in M3).
 * Every change is audited in the same transaction; rejections are audited even though the request fails.
 */
@Service
public class SubscriptionService {

    public static final String CREATED = "SUBSCRIPTION_CREATED";
    public static final String UPDATED = "SUBSCRIPTION_UPDATED";
    public static final String CHANGE_REJECTED = "SUBSCRIPTION_CHANGE_REJECTED";
    public static final String MANUAL_LABEL = "Manual record, no payment verified";
    private static final String TARGET = "BUSINESS_SUBSCRIPTION";

    private final BusinessSubscriptionRepository subscriptions;
    private final SubscriptionPlanRepository plans;
    private final MonetizationConfigRepository configs;
    private final BusinessRepository businesses;
    private final UserRepository users;
    private final EntitlementService entitlements;
    private final AdminAuditService audit;
    private final TransactionTemplate transaction;
    private final Clock clock = Clock.systemUTC();

    public SubscriptionService(BusinessSubscriptionRepository subscriptions, SubscriptionPlanRepository plans,
                               MonetizationConfigRepository configs, BusinessRepository businesses, UserRepository users,
                               EntitlementService entitlements, AdminAuditService audit, PlatformTransactionManager transactionManager) {
        this.subscriptions = subscriptions;
        this.plans = plans;
        this.configs = configs;
        this.businesses = businesses;
        this.users = users;
        this.entitlements = entitlements;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager); // a change and its audit row commit together
    }

    // ------------------------------------------------------------------------------------------------------- reads

    public List<SubscriptionPlanResponse> plans() {
        List<MonetizationModel> enabled = configs.findTopByOrderByVersionDesc().map(MonetizationConfig::models).orElse(List.of());
        return plans.findAllByOrderByCodeAsc().stream().map(p -> new SubscriptionPlanResponse(p.getCode(), p.getModel(), p.getSetting(),
                p.getAdSource(), p.isPaid(), enabled.contains(MonetizationModel.valueOf(p.getModel())))).toList();
    }

    public AdminBusinessPage businesses(String query, int page, int size) {
        PageRequest request = PageRequest.of(page, size, Sort.by("name").ascending().and(Sort.by("id")));
        Page<Business> result = query == null || query.isBlank() ? businesses.findAll(request)
                : businesses.search("%" + query.trim().toLowerCase(Locale.ROOT) + "%", request);
        Map<String, BusinessSubscription> current = subscriptions.findCurrentByBusinessIds(result.getContent().stream().map(Business::getId).toList())
                .stream().collect(Collectors.toMap(BusinessSubscription::getBusinessId, Function.identity()));
        List<AdminBusinessPage.Item> items = result.getContent().stream().map(b -> {
            BusinessSubscription s = current.get(b.getId());
            return new AdminBusinessPage.Item(b.getId(), b.getName(), b.getCustomSubdomain(), b.getCreatedAt(),
                    s == null ? null : s.getStatus(), s == null ? null : s.getPlanCode());
        }).toList();
        return new AdminBusinessPage(items, page, size, result.getTotalElements());
    }

    public BusinessSubscriptionView view(String businessId) {
        Business business = businesses.findById(businessId).orElseThrow(() -> new NotFoundException("Restaurant not found"));
        MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
        SubscriptionResponse current = subscriptions.findCurrentByBusinessId(businessId).map(s -> response(s, config)).orElse(null);
        return new BusinessSubscriptionView(businessId, business.getName(), current, entitlements.forBusiness(businessId),
                entitlements.isEnabled() && config != null);
    }

    public List<SubscriptionResponse> history(String businessId) {
        if (!businesses.existsById(businessId)) throw new NotFoundException("Restaurant not found");
        MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
        return subscriptions.findByBusinessIdOrderByCreatedAtDesc(businessId).stream().map(s -> response(s, config)).toList();
    }

    // ----------------------------------------------------------------------------------------------------- changes

    public SubscriptionResponse create(String businessId, SubscriptionCreateRequest request, String adminId, String adminEmail) {
        String actor = LogMasking.maskEmail(adminEmail);
        Instant now = clock.instant();
        try {
            validateCreate(request, now);
            if (!businesses.existsById(businessId)) throw new NotFoundException("Restaurant not found");
            return transaction.execute(status -> {
                MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElseThrow(MonetizationNotConfiguredException::new);
                if (!config.getVersion().equals(request.expectedConfigVersion())) throw new ConfigVersionConflictException();
                if (!config.models().contains(MonetizationModel.valueOf(request.planCode()))) {
                    throw new FieldValidationException(Map.of("planCode", "This plan isn't enabled by the current configuration"));
                }
                if (subscriptions.findCurrentByBusinessId(businessId).isPresent()) throw new SubscriptionExistsException();
                BusinessSubscription saved = subscriptions.saveAndFlush(BusinessSubscription.manual(businessId, request.planCode(),
                        config.getVersion(), request.periodEnd(), now, adminId));
                audit.success(CREATED, adminId, actor, TARGET, saved.getId(), saved.getPlanCode() + " " + saved.getStatus()
                        + " source=" + saved.getSource() + " config=" + saved.getConfigVersion() + " periodEnd=" + saved.getPeriodEnd());
                return response(saved, config);
            });
        } catch (DataIntegrityViolationException e) {
            // a concurrent create won on the partial unique index; anything else is a real error
            if (subscriptions.findCurrentByBusinessId(businessId).isEmpty()) throw e;
            reject(adminId, actor, "exists");
            throw new SubscriptionExistsException();
        } catch (RuntimeException e) {
            String reason = reason(e);
            if (reason != null) reject(adminId, actor, reason);
            throw e;
        }
    }

    public SubscriptionResponse update(String businessId, SubscriptionUpdateRequest request, String adminId, String adminEmail) {
        String actor = LogMasking.maskEmail(adminEmail);
        Instant now = clock.instant();
        try {
            validateUpdate(request, now);
            return transaction.execute(status -> {
                BusinessSubscription current = subscriptions.findCurrentByBusinessId(businessId)
                        .orElseThrow(() -> new NotFoundException("No current subscription"));
                if (!current.getRowVersion().equals(request.rowVersion())) throw new SubscriptionVersionConflictException();
                String statusBefore = current.getStatus();
                Instant periodEndBefore = current.getPeriodEnd();
                if (request.periodEnd() != null) current.changePeriodEnd(request.periodEnd(), now, adminId);
                if (BusinessSubscription.EXPIRED.equals(request.status())) current.end(now, adminId);
                BusinessSubscription saved = subscriptions.saveAndFlush(current);
                audit.success(UPDATED, adminId, actor, TARGET, saved.getId(), saved.getPlanCode() + " " + statusBefore + " -> "
                        + saved.getStatus() + " periodEnd " + periodEndBefore + " -> " + saved.getPeriodEnd() + " source=" + saved.getSource());
                return response(saved, configs.findTopByOrderByVersionDesc().orElse(null));
            });
        } catch (OptimisticLockingFailureException e) {
            reject(adminId, actor, "version_conflict");
            throw new SubscriptionVersionConflictException();
        } catch (RuntimeException e) {
            String reason = reason(e);
            if (reason != null) reject(adminId, actor, reason);
            throw e;
        }
    }

    private void validateCreate(SubscriptionCreateRequest request, Instant now) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.planCode() == null || request.planCode().isBlank()) {
            errors.put("planCode", "Choose a plan");
        } else if (SubscriptionPlan.APPROVED.stream().noneMatch(p -> p.getCode().equals(request.planCode()))) {
            errors.put("planCode", "Unknown plan");
        }
        if (request.periodEnd() != null && !request.periodEnd().isAfter(now)) errors.put("periodEnd", "Choose a date in the future");
        if (request.expectedConfigVersion() == null || request.expectedConfigVersion() < 0) {
            errors.put("expectedConfigVersion", "Reload and try again");
        }
        if (!errors.isEmpty()) throw new FieldValidationException(errors);
    }

    private void validateUpdate(SubscriptionUpdateRequest request, Instant now) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.planCode() != null) errors.put("planCode", "Plans can't be changed");
        if (request.status() != null && !BusinessSubscription.EXPIRED.equals(request.status())) {
            errors.put("status", "A subscription can only be ended");
        }
        if (request.status() == null && request.periodEnd() == null && request.planCode() == null) {
            errors.put("status", "Nothing to change");
        }
        if (request.periodEnd() != null && !request.periodEnd().isAfter(now)) errors.put("periodEnd", "Choose a date in the future");
        if (request.rowVersion() == null) errors.put("rowVersion", "Reload and try again");
        if (!errors.isEmpty()) throw new FieldValidationException(errors);
    }

    /** The audit reason category for an expected rejection, or null for unexpected errors (not audited as rejections). */
    private static String reason(RuntimeException e) {
        if (e instanceof FieldValidationException) return "invalid";
        if (e instanceof NotFoundException) return "not_found";
        if (e instanceof MonetizationNotConfiguredException) return "not_configured";
        if (e instanceof ConfigVersionConflictException || e instanceof SubscriptionVersionConflictException) return "version_conflict";
        if (e instanceof SubscriptionExistsException) return "exists";
        return null;
    }

    private void reject(String adminId, String actor, String reason) {
        audit.failure(CHANGE_REJECTED, adminId, actor, reason);
    }

    private SubscriptionResponse response(BusinessSubscription s, MonetizationConfig config) {
        boolean outside = s.isCurrent() && config != null && !config.models().contains(MonetizationModel.valueOf(s.getPlanCode()));
        boolean manual = BusinessSubscription.MANUAL.equals(s.getSource());
        String createdBy = users.findById(s.getCreatedByUserId()).map(u -> LogMasking.maskEmail(u.getEmail())).orElse("unknown");
        return new SubscriptionResponse(s.getId(), s.getBusinessId(), s.getPlanCode(), s.getStatus(), s.getConfigVersion(),
                s.getPeriodEnd(), s.getTransitionDate(), s.getSource(), false, manual ? MANUAL_LABEL : null, s.getCreatedAt(),
                createdBy, s.getUpdatedAt(), s.getEndedAt(), s.getRowVersion(), outside);
    }
}
