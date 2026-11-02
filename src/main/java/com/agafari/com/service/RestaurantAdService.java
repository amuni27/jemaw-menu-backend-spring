package com.agafari.com.service;

import com.agafari.com.dto.request.AdActionRequest;
import com.agafari.com.dto.request.AdImageRequest;
import com.agafari.com.dto.request.RestaurantAdRequest;
import com.agafari.com.dto.response.PresignLogoResponse;
import com.agafari.com.dto.response.RestaurantAdResponse;
import com.agafari.com.dto.response.RestaurantAdsView;
import com.agafari.com.exception.AdArchivedException;
import com.agafari.com.exception.AdSuspendedException;
import com.agafari.com.exception.AdVersionConflictException;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.exception.RestaurantAdLimitException;
import com.agafari.com.exception.RestaurantAdsNotEntitledException;
import com.agafari.com.jpa.entity.AdContent;
import com.agafari.com.jpa.entity.Business;
import com.agafari.com.jpa.entity.RestaurantAd;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.jpa.repository.RestaurantAdRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.security.LogMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * AGF E4-M8: the owner's own restaurant ads (Model 3). The restaurant always comes from the signed-in owner; every lookup is
 * scoped to it, so another restaurant's ad is "not found". Order of checks: ownership (404), entitlement (403), lifecycle
 * (409), validation (400). Writes need {@link #canManage}: the {@code app.monetization.restaurant-ads-enabled} flag (default
 * false) and the restaurant ad manager entitlement (current Model 3), checked here on every write; otherwise the owner may
 * only read and archive (Q-M8-9). Content limits as approved (Q-M8-1), schedules in the restaurant's zone (Q-M8-7), at most
 * {@link #SAFETY_CAP} non-archived ads (Q-M8-5, not a plan limit), never deleted, every change and rejection audited.
 */
@Slf4j
@Service
public class RestaurantAdService {

    /** Q-M8-5: a technical safety cap, separate from any commercial plan limit (E4-Q3 deferred). */
    public static final int SAFETY_CAP = 20;

    public static final String CREATED = "RESTAURANT_AD_CREATED";
    public static final String UPDATED = "RESTAURANT_AD_UPDATED";
    public static final String PAUSED = "RESTAURANT_AD_PAUSED";
    public static final String RESUMED = "RESTAURANT_AD_RESUMED";
    public static final String ARCHIVED = "RESTAURANT_AD_ARCHIVED";
    public static final String IMAGE_CHANGED = "RESTAURANT_AD_IMAGE_CHANGED";
    public static final String REJECTED = "RESTAURANT_AD_CHANGE_REJECTED";
    static final String TARGET = "RESTAURANT_AD";

    private final RestaurantAdRepository ads;
    private final BusinessRepository businesses;
    private final EntitlementService entitlements;
    private final MonetizationLocks locks;
    private final MonetizationClock clock;
    private final AdminAuditService audit;
    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final TransactionTemplate transaction;
    private final String bucket;
    private final String publicBaseUrl;
    private volatile boolean enabled;

    public RestaurantAdService(RestaurantAdRepository ads, BusinessRepository businesses, EntitlementService entitlements, MonetizationLocks locks,
                               MonetizationClock clock, AdminAuditService audit, S3Client s3Client, S3Presigner presigner,
                               PlatformTransactionManager transactionManager, @Value("${cloudflare.bucket}") String bucket,
                               @Value("${cloudflare.publicBaseUrl}") String publicBaseUrl,
                               @Value("${app.monetization.restaurant-ads-enabled:false}") boolean enabled) {
        this.ads = ads;
        this.businesses = businesses;
        this.entitlements = entitlements;
        this.locks = locks;
        this.clock = clock;
        this.audit = audit;
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.transaction = new TransactionTemplate(transactionManager);
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Tests only: the flag itself comes from configuration. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** The flag is on and the restaurant's entitlements include the restaurant ad manager. Degraded entitlements → false. */
    public boolean canManage(String businessId) {
        return enabled && entitlements.forBusiness(businessId).restaurantAdManager();
    }

    // ------------------------------------------------------------------------------------------------------- reads

    public RestaurantAdsView view(String businessId) {
        return new RestaurantAdsView(canManage(businessId), SAFETY_CAP, ads.countNonArchived(businessId), zone(businessId).getId(),
                ads.findAllByBusinessIdOrderByCreatedAtDesc(businessId).stream().map(a -> response(a, null)).toList());
    }

    // ----------------------------------------------------------------------------------------------------- changes

    public RestaurantAdResponse create(String businessId, RestaurantAdRequest request, String userId, String email) {
        return audited(businessId, userId, email, actor -> {
            requireManage(businessId);
            RestaurantAd saved = transaction.execute(status -> {
                locks.restaurant(businessId); // the cap holds under concurrent creates
                if (ads.countNonArchived(businessId) >= SAFETY_CAP) throw new RestaurantAdLimitException(SAFETY_CAP);
                RestaurantAd ad = ads.saveAndFlush(RestaurantAd.create(businessId, validate(businessId, request), clock.now(), userId));
                audit.success(CREATED, userId, actor, TARGET, ad.getId(), describe(ad));
                return ad;
            });
            return response(saved, null);
        });
    }

    /** Content and schedule only; a suspended ad stays suspended. */
    public RestaurantAdResponse update(String businessId, String id, RestaurantAdRequest request, String userId, String email) {
        return audited(businessId, userId, email, actor -> change(businessId, id, request.rowVersion(), true,
                ad -> ad.apply(validate(businessId, request), clock.now(), userId), UPDATED, userId, actor));
    }

    public RestaurantAdResponse pause(String businessId, String id, AdActionRequest request, String userId, String email) {
        return audited(businessId, userId, email, actor -> change(businessId, id, request.rowVersion(), true, ad -> {
            if (RestaurantAd.SUSPENDED.equals(ad.getStatus())) throw new AdSuspendedException();
            if (!RestaurantAd.ACTIVE.equals(ad.getStatus())) throw new FieldValidationException(Map.of("status", "This ad isn't running"));
            ad.changeStatus(RestaurantAd.PAUSED, clock.now(), userId);
        }, PAUSED, userId, actor));
    }

    public RestaurantAdResponse resume(String businessId, String id, AdActionRequest request, String userId, String email) {
        return audited(businessId, userId, email, actor -> change(businessId, id, request.rowVersion(), true, ad -> {
            if (RestaurantAd.SUSPENDED.equals(ad.getStatus())) throw new AdSuspendedException();
            if (!RestaurantAd.PAUSED.equals(ad.getStatus())) throw new FieldValidationException(Map.of("status", "This ad isn't paused"));
            ad.changeStatus(RestaurantAd.ACTIVE, clock.now(), userId);
        }, RESUMED, userId, actor));
    }

    /** Always allowed for the owner (also suspended, also without the entitlement). */
    public RestaurantAdResponse archive(String businessId, String id, AdActionRequest request, String userId, String email) {
        return audited(businessId, userId, email, actor -> change(businessId, id, request.rowVersion(), false,
                ad -> ad.changeStatus(RestaurantAd.ARCHIVED, clock.now(), userId), ARCHIVED, userId, actor));
    }

    // ------------------------------------------------------------------------------------------------------ images

    public PresignLogoResponse presignImage(String businessId, String id, AdImageRequest request, String userId, String email) {
        String[] key = new String[1];
        audited(businessId, userId, email, actor -> {
            RestaurantAd ad = ads.findByIdAndBusinessId(id, businessId).orElseThrow(() -> new NotFoundException("Ad not found"));
            requireManage(businessId);
            if (RestaurantAd.ARCHIVED.equals(ad.getStatus())) throw new AdArchivedException();
            key[0] = ImagePolicy.objectKey(prefix(businessId, id), request.contentType()); // allowed image types only (S1-17)
            return null;
        });
        PutObjectRequest put = PutObjectRequest.builder().bucket(bucket).key(key[0]).contentType(ImagePolicy.normalize(request.contentType())).build();
        String url = presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(5)).putObjectRequest(put).build()).url().toString();
        return PresignLogoResponse.builder().objectKey(key[0]).uploadUrl(url).expiresInSeconds(300).build();
    }

    public RestaurantAdResponse confirmImage(String businessId, String id, AdImageRequest request, String userId, String email) {
        return audited(businessId, userId, email, actor -> {
            RestaurantAd found = ads.findByIdAndBusinessId(id, businessId).orElseThrow(() -> new NotFoundException("Ad not found"));
            requireManage(businessId);
            if (RestaurantAd.ARCHIVED.equals(found.getStatus())) throw new AdArchivedException();
            Map<String, String> errors = new LinkedHashMap<>();
            String alt = AdContentRules.trim(request.imageAlt());
            if (alt == null || alt.length() > 200) errors.put("imageAlt", "Describe the image (at most 200 characters)");
            if (request.rowVersion() == null) errors.put("rowVersion", "Reload and try again");
            if (!errors.isEmpty()) throw new FieldValidationException(errors);
            String key = request.objectKey() == null ? null : request.objectKey().trim();
            String ext = ImagePolicy.extensionOfKey(key, prefix(businessId, id)); // only this restaurant's key for this ad
            if (ext == null) throw new BadRequestException("objectKey does not match this ad's image upload");
            HeadObjectResponse head;
            try {
                head = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            } catch (Exception e) {
                throw new BadRequestException("Image not found in storage (upload not completed)");
            }
            String problem = ImagePolicy.problemWith(head, ext);
            if (problem != null) {
                deleteQuietly(businessId, id, key);
                throw new BadRequestException(problem);
            }
            String[] previous = new String[1];
            RestaurantAdResponse response = change(businessId, id, request.rowVersion(), true, ad -> {
                previous[0] = ad.getImageKey();
                ad.changeImage(key, alt, clock.now(), userId);
            }, IMAGE_CHANGED, userId, actor);
            if (previous[0] != null && !previous[0].equals(key)) deleteQuietly(businessId, id, previous[0]);
            return response;
        });
    }

    public RestaurantAdResponse removeImage(String businessId, String id, AdActionRequest request, String userId, String email) {
        return audited(businessId, userId, email, actor -> {
            String[] previous = new String[1];
            RestaurantAdResponse response = change(businessId, id, request.rowVersion(), true, ad -> {
                previous[0] = ad.getImageKey();
                ad.changeImage(null, null, clock.now(), userId);
            }, IMAGE_CHANGED, userId, actor);
            if (previous[0] != null) deleteQuietly(businessId, id, previous[0]);
            return response;
        });
    }

    public String publicImageUrl(String imageKey) {
        return LogoUrls.publicUrl(publicBaseUrl, imageKey);
    }

    // ----------------------------------------------------------------------------------------------------- helpers

    interface Change {
        void apply(RestaurantAd ad);
    }

    private RestaurantAdResponse change(String businessId, String id, Integer rowVersion, boolean needsManage, Change change, String action,
                                        String userId, String actor) {
        RestaurantAd saved = transaction.execute(status -> {
            RestaurantAd ad = ads.findByIdAndBusinessId(id, businessId).orElseThrow(() -> new NotFoundException("Ad not found"));
            if (needsManage) requireManage(businessId);
            if (RestaurantAd.ARCHIVED.equals(ad.getStatus())) throw new AdArchivedException();
            if (rowVersion == null) throw new FieldValidationException(Map.of("rowVersion", "Reload and try again"));
            if (!ad.getRowVersion().equals(rowVersion)) throw new AdVersionConflictException();
            change.apply(ad);
            RestaurantAd result = ads.saveAndFlush(ad);
            audit.success(action, userId, actor, TARGET, result.getId(), describe(result));
            return result;
        });
        return response(saved, null);
    }

    private void requireManage(String businessId) {
        if (!canManage(businessId)) throw new RestaurantAdsNotEntitledException();
    }

    private RestaurantAdResponse audited(String businessId, String userId, String email, Function<String, RestaurantAdResponse> work) {
        String actor = LogMasking.maskEmail(email);
        try {
            return work.apply(actor);
        } catch (OptimisticLockingFailureException e) {
            reject(userId, actor, businessId, "stale");
            throw new AdVersionConflictException();
        } catch (FieldValidationException | BadRequestException e) {
            reject(userId, actor, businessId, "invalid");
            throw e;
        } catch (AdVersionConflictException e) {
            reject(userId, actor, businessId, "stale");
            throw e;
        } catch (AdArchivedException e) {
            reject(userId, actor, businessId, "archived");
            throw e;
        } catch (AdSuspendedException e) {
            reject(userId, actor, businessId, "suspended");
            throw e;
        } catch (RestaurantAdLimitException e) {
            reject(userId, actor, businessId, "cap");
            throw e;
        } catch (RestaurantAdsNotEntitledException e) {
            reject(userId, actor, businessId, "not-entitled");
            throw e;
        } catch (NotFoundException e) {
            reject(userId, actor, businessId, "not-found");
            throw e;
        }
    }

    private void reject(String userId, String actor, String businessId, String reason) {
        audit.failure(REJECTED, userId, actor, TARGET, businessId, reason);
    }

    private AdContent validate(String businessId, RestaurantAdRequest r) {
        Map<String, String> errors = new LinkedHashMap<>();
        AdContentRules.Text text = AdContentRules.check(r.title(), r.body(), r.titleAm(), r.bodyAm(), r.linkUrl(), errors);
        ZoneId zone = zone(businessId);
        Instant start = null, end = null;
        if (r.startLocal() == null || r.startLocal().isBlank()) errors.put("startLocal", "Enter a start date and time");
        else start = AdSchedule.toInstant("startLocal", "startOffset", r.startLocal(), r.startOffset(), zone, errors);
        if (r.endLocal() != null && !r.endLocal().isBlank()) {
            end = AdSchedule.toInstant("endLocal", "endOffset", r.endLocal(), r.endOffset(), zone, errors);
            if (start != null && end != null && !end.isAfter(start)) errors.put("endLocal", "The end must be after the start");
        }
        if (!errors.isEmpty()) throw new FieldValidationException(errors);
        return new AdContent(text.title(), text.body(), text.titleAm(), text.bodyAm(), text.linkUrl(), start, end, zone.getId());
    }

    private ZoneId zone(String businessId) {
        return TimeZoneRules.zoneId(businesses.findById(businessId).map(Business::getTimeZone).orElse(null));
    }

    RestaurantAdResponse response(RestaurantAd a, String businessName) {
        ZoneId zone = ZoneId.of(a.getScheduleTimeZone());
        return new RestaurantAdResponse(a.getId(), a.getBusinessId(), businessName, a.getTitle(), a.getBody(), a.getTitleAm(), a.getBodyAm(),
                a.getLinkUrl(), publicImageUrl(a.getImageKey()), a.getImageAlt(), a.getStartsAt(), a.getEndsAt(), a.getScheduleTimeZone(),
                AdSchedule.local(a.getStartsAt(), zone), AdSchedule.offset(a.getStartsAt(), zone), AdSchedule.local(a.getEndsAt(), zone),
                AdSchedule.offset(a.getEndsAt(), zone), a.getStatus(), a.getSuspensionReason(), a.getSuspendedAt(), a.getCreatedAt(),
                a.getUpdatedAt(), a.getRowVersion());
    }

    static String describe(RestaurantAd a) {
        return "business=" + a.getBusinessId() + " status=" + a.getStatus() + " starts=" + a.getStartsAt() + " ends=" + a.getEndsAt()
                + " image=" + (a.getImageKey() != null);
    }

    /** Restaurant-scoped image key prefix (Q-M8-13): one restaurant can never confirm another's object. */
    static String prefix(String businessId, String id) {
        return "restaurant-ad-asset/" + businessId + "/" + id;
    }

    private void deleteQuietly(String businessId, String id, String key) {
        if (!key.startsWith(prefix(businessId, id) + "/")) return;
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception e) {
            log.warn("event=restaurant_ad.image.delete_failed key={} errorClass={}", key, e.getClass().getSimpleName());
        }
    }
}
