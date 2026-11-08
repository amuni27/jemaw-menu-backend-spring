package com.agafari.com.service;

import com.agafari.com.exception.ProviderPeriodManagedByBillingException;
import com.agafari.com.subscription.Actor;
import com.agafari.com.subscription.AdminPeriodChange;
import com.agafari.com.subscription.IllegalTransitionException;
import com.agafari.com.subscription.ManualRecord;
import com.agafari.com.subscription.SubscriptionLifecycle;
import com.agafari.com.dto.request.SubscriptionCreateRequest;
import com.agafari.com.dto.request.SubscriptionUpdateRequest;
import com.agafari.com.dto.request.TransitionDateRequest;
import com.agafari.com.dto.response.AdminBusinessPage;
import com.agafari.com.dto.response.BusinessSubscriptionView;
import com.agafari.com.dto.response.GrandfatheringInfo;
import com.agafari.com.dto.response.PlanRequestSummary;
import com.agafari.com.dto.response.SubscriptionPlanResponse;
import com.agafari.com.dto.response.SubscriptionResponse;
import com.agafari.com.dto.response.TrialIntentSummary;
import com.agafari.com.enums.MonetizationModel;
import com.agafari.com.exception.ConfigVersionConflictException;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.exception.MonetizationNotConfiguredException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.exception.PlanRequestPendingException;
import com.agafari.com.exception.TrialEndedException;
import com.agafari.com.exception.SubscriptionExistsException;
import com.agafari.com.exception.SubscriptionVersionConflictException;
import com.agafari.com.jpa.entity.Business;
import com.agafari.com.jpa.entity.BusinessSubscription;
import com.agafari.com.jpa.entity.MonetizationConfig;
import com.agafari.com.jpa.entity.SubscriptionPlan;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.jpa.repository.BusinessSubscriptionRepository;
import com.agafari.com.jpa.repository.MonetizationConfigRepository;
import com.agafari.com.jpa.repository.PlanRequestRepository;
import com.agafari.com.jpa.repository.SubscriptionPlanRepository;
import com.agafari.com.jpa.repository.TrialIntentRepository;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
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
    public static final String TRIAL_LABEL = "Trial (automatic), no payment involved";
    public static final String OWNER_LABEL = "Self-service (owner), no payment"; // AGF Self-Service Phase A (SS-4)
    public static final String TRIAL_EXTENDED = "TRIAL_EXTENDED";
    public static final String TRIAL_ENDED = "TRIAL_ENDED";
    private static final String TARGET = "BUSINESS_SUBSCRIPTION";

    private final BusinessSubscriptionRepository subscriptions;
    private final SubscriptionPlanRepository plans;
    private final MonetizationConfigRepository configs;
    private final BusinessRepository businesses;
    private final UserRepository users;
    private final EntitlementService entitlements;
    private final AdminAuditService audit;
    private final TransactionTemplate transaction;
    private final PlanRequestRepository planRequests;
    private final MonetizationLocks locks;
    private final TrialExpirer trialExpirer;
    private final MonetizationClock monetizationClock;
    private final TrialIntentRepository trialIntents;
    private final GrandfatheringService grandfathering;
    private final SubscriptionLifecycle lifecycle;
    private final Clock clock = Clock.systemUTC();

    public SubscriptionService(BusinessSubscriptionRepository subscriptions, SubscriptionPlanRepository plans,
                               MonetizationConfigRepository configs, BusinessRepository businesses, UserRepository users,
                               EntitlementService entitlements, AdminAuditService audit, PlatformTransactionManager transactionManager,
                               PlanRequestRepository planRequests, MonetizationLocks locks, TrialExpirer trialExpirer,
                               MonetizationClock monetizationClock, TrialIntentRepository trialIntents, GrandfatheringService grandfathering,
                               SubscriptionLifecycle lifecycle) {
        this.lifecycle = lifecycle;
        this.grandfathering = grandfathering;
        this.trialIntents = trialIntents;
        this.planRequests = planRequests;
        this.locks = locks;
        this.trialExpirer = trialExpirer;
        this.monetizationClock = monetizationClock;
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
        return businesses(query, page, size, false);
    }

    /** AGF E4-M6: {@code grandfatheredOnly} lists only restaurants whose current subscription is grandfathered. */
    public AdminBusinessPage businesses(String query, int page, int size, boolean grandfatheredOnly) {
        PageRequest request = PageRequest.of(page, size, Sort.by("name").ascending().and(Sort.by("id")));
        String pattern = query == null || query.isBlank() ? null : query.trim().toLowerCase(Locale.ROOT);
        List<Business> content;
        long total;
        if (grandfatheredOnly) {
            List<Business> all = businesses.findAllById(grandfathering.grandfatheredBusinessIds()).stream()
                    .filter(b -> pattern == null || b.getName().toLowerCase(Locale.ROOT).contains(pattern) || b.getCustomSubdomain().toLowerCase(Locale.ROOT).contains(pattern))
                    .sorted(Comparator.comparing(Business::getName).thenComparing(Business::getId)).toList();
            total = all.size();
            content = all.stream().skip((long) page * size).limit(size).toList();
        } else {
            Page<Business> result = pattern == null ? businesses.findAll(request) : businesses.search("%" + pattern + "%", request);
            content = result.getContent();
            total = result.getTotalElements();
        }
        MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
        Map<String, BusinessSubscription> current = subscriptions.findCurrentByBusinessIds(content.stream().map(Business::getId).toList())
                .stream().collect(Collectors.toMap(BusinessSubscription::getBusinessId, Function.identity()));
        List<AdminBusinessPage.Item> items = content.stream().map(b -> {
            BusinessSubscription s = current.get(b.getId());
            String status = s == null || grandfathering.effectivelyEnded(s, config) ? null : s.effectiveStatus(monetizationClock.now());
            boolean shown = status != null && !BusinessSubscription.EXPIRED.equals(status);
            return new AdminBusinessPage.Item(b.getId(), b.getName(), b.getCustomSubdomain(), b.getCreatedAt(),
                    shown ? status : null, shown ? s.getPlanCode() : null, shown && grandfathering.assess(s, config).grandfathered());
        }).toList();
        return new AdminBusinessPage(items, page, size, total);
    }

    public BusinessSubscriptionView view(String businessId) {
        Business business = businesses.findById(businessId).orElseThrow(() -> new NotFoundException("Restaurant not found"));
        MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
        SubscriptionResponse current = subscriptions.findCurrentByBusinessId(businessId).map(s -> response(s, config)).orElse(null);
        PlanRequestSummary pending = planRequests.findPendingByBusinessId(businessId)
                .map(r -> new PlanRequestSummary(r.getId(), r.getPlanCode(), r.getConfigVersion(), r.getRequestedAt(), r.getRowVersion(),
                        config != null && !config.models().contains(MonetizationModel.valueOf(r.getPlanCode())))).orElse(null);
        return new BusinessSubscriptionView(businessId, business.getName(), current, entitlements.forBusiness(businessId),
                entitlements.isEnabled() && config != null, pending, business.getTimeZone(),
                trialIntents.findById(businessId).map(i -> TrialIntentSummary.of(i, null)).orElse(null));
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
            trialExpirer.expireDueOnTouch(businessId); // E4-M5: an effectively ended trial no longer blocks a record
            grandfathering.expireDueOnTouch(businessId); // E4-M6: neither does a grandfathered subscription past its end (flag on)
            return transaction.execute(status -> {
                locks.configShared(); // E4-M4 §16.2: no configuration change between this check and the commit
                locks.restaurant(businessId); // E4-M4 §16.1: serialized with owner plan requests for this restaurant
                MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElseThrow(MonetizationNotConfiguredException::new);
                if (!config.getVersion().equals(request.expectedConfigVersion())) throw new ConfigVersionConflictException();
                if (!config.models().contains(MonetizationModel.valueOf(request.planCode()))) {
                    throw new FieldValidationException(Map.of("planCode", "This plan isn't enabled by the current configuration"));
                }
                if (subscriptions.findCurrentByBusinessId(businessId).isPresent()) throw new SubscriptionExistsException();
                if (planRequests.findPendingByBusinessId(businessId).isPresent()) throw new PlanRequestPendingException(); // invariant I1
                return response(recordWithinTransaction(businessId, request.planCode(), config.getVersion(), request.periodEnd(), now,
                        adminId, actor), config);
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

    /**
     * Inserts the manual ACTIVE record and its audit row. Callers hold the configuration and restaurant locks and have
     * checked the configuration, the plan and that there is no current subscription (E4-M3 create, E4-M4 fulfil).
     */
    BusinessSubscription recordWithinTransaction(String businessId, String planCode, int configVersion, Instant periodEnd, Instant now,
                                                 String adminId, String actor) {
        BusinessSubscription saved = lifecycle.recordManual(businessId, new ManualRecord(planCode, configVersion, periodEnd, now), new Actor(adminId)); // AGF SUB-R2-02
        auditManualRecord(saved, adminId, actor);
        return saved;
    }

    /** The audit row of a new manual record (also written for a trial conversion's successor). */
    void auditManualRecord(BusinessSubscription saved, String adminId, String actor) {
        audit.success(CREATED, adminId, actor, TARGET, saved.getId(), saved.getPlanCode() + " " + saved.getStatus()
                + " source=" + saved.getSource() + " config=" + saved.getConfigVersion() + " periodEnd=" + saved.getPeriodEnd());
    }

    public SubscriptionResponse update(String businessId, SubscriptionUpdateRequest request, String adminId, String adminEmail) {
        String actor = LogMasking.maskEmail(adminEmail);
        Instant now = clock.instant();
        try {
            validateUpdate(request, now);
            if (trialExpirer.expireDueOnTouch(businessId)) throw new TrialEndedException(); // E4-M5: an ended trial can't be extended
            grandfathering.expireDueOnTouch(businessId); // E4-M6 (flag on): a grandfathered subscription past its end is ended first
            return transaction.execute(status -> {
                locks.configShared(); // E4-M6: the grandfathering checks below use the configuration current at commit
                locks.restaurant(businessId); // E4-M5: serialized with the expiry job and trial conversion
                BusinessSubscription current = subscriptions.findCurrentByBusinessId(businessId)
                        .orElseThrow(() -> new NotFoundException("No current subscription"));
                if (!current.getRowVersion().equals(request.rowVersion())) throw new SubscriptionVersionConflictException();
                if (request.periodEnd() != null || request.periodEndDate() != null) lifecycle.requireAdminPeriodEditable(current); // AGF SUB-R2-03 (R-4)
                String statusBefore = current.getStatus();
                Instant periodEndBefore = current.getPeriodEnd();
                String transitionNote = "";
                Instant newPeriodEnd = request.periodEnd();
                boolean clearTransitionDate = false;
                if (request.periodEnd() != null) {
                    MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElse(null);
                    // E4-M6 Q11: while grandfathered, an existing end date can be extended but never shortened
                    if (periodEndBefore != null && request.periodEnd().isBefore(periodEndBefore) && grandfathering.assess(current, config).grandfathered()) {
                        throw new FieldValidationException(Map.of("periodEnd", "The end date can only be extended while the subscription is grandfathered"));
                    }
                    // E4-M6 Q12 (D-M6-2): a period end on an open-ended subscription becomes authoritative and clears any transition date
                    if (periodEndBefore == null && current.getTransitionDate() != null) {
                        transitionNote = " transitionDate " + current.getTransitionDate() + " -> cleared";
                        clearTransitionDate = true;
                    }
                }
                if (request.periodEndDate() != null) newPeriodEnd = trialEndOf(businessId, current, LocalDate.parse(request.periodEndDate()), now);
                BusinessSubscription saved = lifecycle.applyAdminChange(current, // AGF SUB-R2-03
                        new AdminPeriodChange(newPeriodEnd, clearTransitionDate, BusinessSubscription.EXPIRED.equals(request.status()), now), new Actor(adminId));
                String action = !saved.isTrial() ? UPDATED : BusinessSubscription.EXPIRED.equals(saved.getStatus()) ? TRIAL_ENDED : TRIAL_EXTENDED;
                audit.success(action, adminId, actor, TARGET, saved.getId(), saved.getPlanCode() + " " + statusBefore + " -> "
                        + saved.getStatus() + " periodEnd " + periodEndBefore + " -> " + saved.getPeriodEnd() + " source=" + saved.getSource() + transitionNote);
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
        if (request.status() == null && request.periodEnd() == null && request.planCode() == null && request.periodEndDate() == null) {
            errors.put("status", "Nothing to change");
        }
        if (request.periodEndDate() != null && request.periodEnd() != null) errors.put("periodEndDate", "Send a date or an end time, not both");
        if (request.periodEndDate() != null && request.status() != null) errors.put("periodEndDate", "Extending and ending are separate actions");
        if (request.periodEndDate() != null && !errors.containsKey("periodEndDate")) {
            try {
                LocalDate.parse(request.periodEndDate()); // ISO yyyy-MM-dd, strict: no time, no zone, no impossible dates
            } catch (DateTimeParseException e) {
                errors.put("periodEndDate", "Use a calendar date (YYYY-MM-DD)");
            }
        }
        if (request.periodEnd() != null && !request.periodEnd().isAfter(now)) errors.put("periodEnd", "Choose a date in the future");
        if (request.rowVersion() == null) errors.put("rowVersion", "Reload and try again");
        if (!errors.isEmpty()) throw new FieldValidationException(errors);
    }

    // ------------------------------------------------------------------------------------------ E4-M6 transition dates

    /**
     * AGF E4-M6 (Q3, Option B, D-M6-1): the transition date of an open-ended grandfathered subscription: the end of the chosen
     * day in the restaurant's time zone. Under the configuration and restaurant locks; recorded with the configuration version
     * in the same transaction (the record is what makes the date valid).
     */
    public SubscriptionResponse setTransitionDate(String businessId, TransitionDateRequest request, String adminId, String adminEmail) {
        return changeTransition(businessId, request, true, adminId, adminEmail);
    }

    public SubscriptionResponse clearTransitionDate(String businessId, TransitionDateRequest request, String adminId, String adminEmail) {
        return changeTransition(businessId, request, false, adminId, adminEmail);
    }

    private SubscriptionResponse changeTransition(String businessId, TransitionDateRequest request, boolean set, String adminId, String adminEmail) {
        String actor = LogMasking.maskEmail(adminEmail);
        Instant now = clock.instant();
        try {
            Map<String, String> errors = new LinkedHashMap<>();
            LocalDate date = null;
            if (set) {
                if (request.transitionDate() == null) errors.put("transitionDate", "Choose a date");
                else {
                    try {
                        date = LocalDate.parse(request.transitionDate()); // ISO yyyy-MM-dd, strict
                    } catch (DateTimeParseException e) {
                        errors.put("transitionDate", "Use a calendar date (YYYY-MM-DD)");
                    }
                }
            }
            if (request.rowVersion() == null) errors.put("rowVersion", "Reload and try again");
            if (!errors.isEmpty()) throw new FieldValidationException(errors);
            LocalDate chosen = date;
            return transaction.execute(status -> {
                locks.configShared();
                locks.restaurant(businessId);
                BusinessSubscription current = subscriptions.findCurrentByBusinessId(businessId).orElseThrow(() -> new NotFoundException("No current subscription"));
                if (!current.getRowVersion().equals(request.rowVersion())) throw new SubscriptionVersionConflictException();
                MonetizationConfig config = configs.findTopByOrderByVersionDesc().orElseThrow(MonetizationNotConfiguredException::new);
                if (current.isTrial()) throw new FieldValidationException(Map.of("transitionDate", "A trial keeps its own end; a transition date isn't allowed"));
                if (current.getPeriodEnd() != null) {
                    throw new FieldValidationException(Map.of("transitionDate", "This subscription ends on its period end; a transition date isn't allowed"));
                }
                if (!grandfathering.assess(current, config).grandfathered()) {
                    throw new FieldValidationException(Map.of("transitionDate", "Only a grandfathered subscription can get a transition date"));
                }
                Instant before = current.getTransitionDate();
                Instant newTransitionDate = null;
                if (set) {
                    ZoneId zone = ZoneId.of(businesses.findById(businessId).orElseThrow(() -> new NotFoundException("Restaurant not found")).getTimeZone());
                    Instant end = chosen.plusDays(1).atStartOfDay(zone).toInstant();
                    if (!end.isAfter(now)) throw new FieldValidationException(Map.of("transitionDate", "Choose a date in the future"));
                    newTransitionDate = end;
                } else if (before == null) {
                    throw new FieldValidationException(Map.of("transitionDate", "There is no transition date to clear"));
                }
                BusinessSubscription saved = lifecycle.changeTransitionDate(current, newTransitionDate, now, new Actor(adminId)); // AGF SUB-R2-03
                audit.success(set ? GrandfatheringService.TRANSITION_SET : GrandfatheringService.TRANSITION_CLEARED, adminId, actor, TARGET, saved.getId(),
                        GrandfatheringService.record(config.getVersion(), set ? saved.getTransitionDate() : before));
                return response(saved, config);
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

    /**
     * AGF E4-M5 (Option A): a trial extended to a calendar date runs through that whole day in the restaurant's configured
     * time zone; the stored, exclusive end is the start of the next local day (java.time handles DST and a missing midnight).
     */
    private Instant trialEndOf(String businessId, BusinessSubscription current, LocalDate date, Instant now) {
        if (!current.isTrial() || !BusinessSubscription.TRIAL.equals(current.getStatus())) {
            throw new FieldValidationException(Map.of("periodEndDate", "Only a running trial can be extended to a date"));
        }
        ZoneId zone = ZoneId.of(businesses.findById(businessId).orElseThrow(() -> new NotFoundException("Restaurant not found")).getTimeZone());
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();
        if (!end.isAfter(now)) throw new FieldValidationException(Map.of("periodEndDate", "Choose a date in the future"));
        return end;
    }

    /** The audit reason category for an expected rejection, or null for unexpected errors (not audited as rejections). */
    private static String reason(RuntimeException e) {
        if (e instanceof FieldValidationException) return "invalid";
        if (e instanceof NotFoundException) return "not_found";
        if (e instanceof MonetizationNotConfiguredException) return "not_configured";
        if (e instanceof ConfigVersionConflictException || e instanceof SubscriptionVersionConflictException) return "version_conflict";
        if (e instanceof SubscriptionExistsException) return "exists";
        if (e instanceof PlanRequestPendingException) return "request_pending";
        if (e instanceof TrialEndedException) return "trial_ended";
        if (e instanceof ProviderPeriodManagedByBillingException) return "provider_period"; // AGF SUB-R2-03
        if (e instanceof IllegalTransitionException) return "transition_not_allowed"; // AGF SUB-R2-03
        return null;
    }

    private void reject(String adminId, String actor, String reason) {
        audit.failure(CHANGE_REJECTED, adminId, actor, reason);
    }

    /** AGF E4-M5: for {@link TrialService}. */
    SubscriptionResponse toResponse(BusinessSubscription s, MonetizationConfig config) {
        return response(s, config);
    }

    private SubscriptionResponse response(BusinessSubscription s, MonetizationConfig config) {
        boolean outside = s.isCurrent() && config != null && !config.models().contains(MonetizationModel.valueOf(s.getPlanCode()));
        String label = BusinessSubscription.MANUAL.equals(s.getSource()) ? MANUAL_LABEL : s.isTrial() ? TRIAL_LABEL
                : BusinessSubscription.OWNER.equals(s.getSource()) ? OWNER_LABEL : null;
        String createdBy = AdminAuditService.SYSTEM.equals(s.getCreatedByUserId()) ? AdminAuditService.SYSTEM
                : users.findById(s.getCreatedByUserId()).map(u -> LogMasking.maskEmail(u.getEmail())).orElse("unknown");
        Instant now = monetizationClock.now();
        GrandfatheringInfo info = grandfathering.info(s, config);
        boolean ended = info != null && info.transitionsEnabled() && info.overdue(); // E4-M6: effective end only with the flag on
        return new SubscriptionResponse(s.getId(), s.getBusinessId(), s.getPlanCode(), s.getStatus(), s.getConfigVersion(),
                s.getPeriodEnd(), s.getTransitionDate(), s.getSource(), false, label, s.getCreatedAt(),
                createdBy, s.getUpdatedAt(), s.getEndedAt(), s.getRowVersion(), outside,
                ended ? BusinessSubscription.EXPIRED : s.effectiveStatus(now), ended ? info.until() : s.effectiveEnd(now), info);
    }
}
