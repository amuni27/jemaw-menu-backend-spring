package com.agafari.com.service;

import com.agafari.com.dto.request.RestaurantAdModerationRequest;
import com.agafari.com.dto.response.RestaurantAdResponse;
import com.agafari.com.exception.AdArchivedException;
import com.agafari.com.exception.AdVersionConflictException;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.entity.Business;
import com.agafari.com.jpa.entity.RestaurantAd;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.jpa.repository.RestaurantAdRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.security.LogMasking;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AGF E4-M8 (Q-M8-2): the Super Admin's oversight of restaurant ads. Lists every restaurant's ads; suspends an ACTIVE or
 * PAUSED ad with a required reason (1–500 characters) and reinstates a suspended ad to PAUSED, so the owner decides when it
 * runs again. Never edits content, creates or archives. Works whatever the flags, so a takedown is always possible. Both
 * actions mark the transaction for the V18 trigger and are audited (the reason in full), rejections too.
 */
@Service
public class RestaurantAdAdminService {

    public static final String SUSPENDED = "RESTAURANT_AD_SUSPENDED";
    public static final String REINSTATED = "RESTAURANT_AD_REINSTATED";
    public static final String REJECTED = "RESTAURANT_AD_ADMIN_REJECTED";

    private final RestaurantAdRepository ads;
    private final BusinessRepository businesses;
    private final RestaurantAdService restaurantAds;
    private final MonetizationLocks locks;
    private final MonetizationClock clock;
    private final AdminAuditService audit;
    private final TransactionTemplate transaction;

    public RestaurantAdAdminService(RestaurantAdRepository ads, BusinessRepository businesses, RestaurantAdService restaurantAds, MonetizationLocks locks,
                                    MonetizationClock clock, AdminAuditService audit, PlatformTransactionManager transactionManager) {
        this.ads = ads;
        this.businesses = businesses;
        this.restaurantAds = restaurantAds;
        this.locks = locks;
        this.clock = clock;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public List<RestaurantAdResponse> list(String businessId, String status) {
        List<RestaurantAd> found = ads.findForAdmin(blankToNull(businessId), blankToNull(status));
        Set<String> ids = found.stream().map(RestaurantAd::getBusinessId).collect(Collectors.toSet());
        Map<String, String> names = businesses.findAllById(ids).stream().collect(Collectors.toMap(Business::getId, Business::getName));
        return found.stream().map(a -> restaurantAds.response(a, names.get(a.getBusinessId()))).toList();
    }

    public RestaurantAdResponse suspend(String id, RestaurantAdModerationRequest request, String adminId, String adminEmail) {
        return audited(id, adminId, adminEmail, actor -> {
            String reason = AdContentRules.trim(request.reason());
            if (reason == null || reason.length() > 500) throw new FieldValidationException(Map.of("reason", "Give a reason (at most 500 characters)"));
            return moderate(id, request.rowVersion(), ad -> {
                if (!RestaurantAd.ACTIVE.equals(ad.getStatus()) && !RestaurantAd.PAUSED.equals(ad.getStatus())) {
                    throw new FieldValidationException(Map.of("status", "Only a running or paused ad can be suspended"));
                }
                ad.suspend(reason, clock.now(), adminId);
            }, SUSPENDED, reason, adminId, actor);
        });
    }

    public RestaurantAdResponse reinstate(String id, RestaurantAdModerationRequest request, String adminId, String adminEmail) {
        return audited(id, adminId, adminEmail, actor -> {
            String note = AdContentRules.trim(request.note());
            if (note != null && note.length() > 500) throw new FieldValidationException(Map.of("note", "At most 500 characters"));
            return moderate(id, request.rowVersion(), ad -> {
                if (!RestaurantAd.SUSPENDED.equals(ad.getStatus())) throw new FieldValidationException(Map.of("status", "This ad isn't suspended"));
                ad.changeStatus(RestaurantAd.PAUSED, clock.now(), adminId);
            }, REINSTATED, note, adminId, actor);
        });
    }

    private RestaurantAdResponse moderate(String id, Integer rowVersion, RestaurantAdService.Change change, String action, String detail,
                                          String adminId, String actor) {
        RestaurantAd saved = transaction.execute(status -> {
            RestaurantAd ad = ads.findById(id).orElseThrow(() -> new NotFoundException("Ad not found"));
            if (RestaurantAd.ARCHIVED.equals(ad.getStatus())) throw new AdArchivedException();
            if (rowVersion == null) throw new FieldValidationException(Map.of("rowVersion", "Reload and try again"));
            if (!ad.getRowVersion().equals(rowVersion)) throw new AdVersionConflictException();
            change.apply(ad);
            locks.markAdminAdModeration();
            RestaurantAd result = ads.saveAndFlush(ad);
            audit.success(action, adminId, actor, RestaurantAdService.TARGET, result.getId(), detail);
            return result;
        });
        return restaurantAds.response(saved, businesses.findById(saved.getBusinessId()).map(Business::getName).orElse(null));
    }

    private RestaurantAdResponse audited(String id, String adminId, String adminEmail, Function<String, RestaurantAdResponse> work) {
        String actor = LogMasking.maskEmail(adminEmail);
        try {
            return work.apply(actor);
        } catch (OptimisticLockingFailureException e) {
            audit.failure(REJECTED, adminId, actor, RestaurantAdService.TARGET, id, "stale");
            throw new AdVersionConflictException();
        } catch (FieldValidationException e) {
            audit.failure(REJECTED, adminId, actor, RestaurantAdService.TARGET, id, "invalid");
            throw e;
        } catch (AdVersionConflictException e) {
            audit.failure(REJECTED, adminId, actor, RestaurantAdService.TARGET, id, "stale");
            throw e;
        } catch (AdArchivedException e) {
            audit.failure(REJECTED, adminId, actor, RestaurantAdService.TARGET, id, "archived");
            throw e;
        } catch (NotFoundException e) {
            audit.failure(REJECTED, adminId, actor, RestaurantAdService.TARGET, id, "not-found");
            throw e;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
