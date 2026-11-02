package com.agafari.com.service;

import com.agafari.com.dto.request.AdActionRequest;
import com.agafari.com.dto.request.AdImageRequest;
import com.agafari.com.dto.request.AdRequest;
import com.agafari.com.dto.response.AdImpressionsResponse;
import com.agafari.com.dto.response.AdResponse;
import com.agafari.com.dto.response.PresignLogoResponse;
import com.agafari.com.exception.AdArchivedException;
import com.agafari.com.exception.AdVersionConflictException;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.exception.FieldValidationException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.entity.AgafariAd;
import com.agafari.com.jpa.repository.AgafariAdRepository;
import com.agafari.com.security.AdminAuditService;
import com.agafari.com.security.LogMasking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AGF E4-M7: the Super Admin's Agafari ad inventory within the E4-Q5 minimum policy (title ≤ 60, text ≤ 200, optional
 * Amharic text, https links only, optional image with alt text through the existing image policy), with schedules entered
 * in an explicit time zone and stored as UTC instants (D-M7-1). Optimistic edits; pause, resume, archive; never deleted;
 * every change audited, rejections too.
 */
@Slf4j
@Service
public class AgafariAdService {

    public static final String CREATED = "AD_CREATED";
    public static final String UPDATED = "AD_UPDATED";
    public static final String PAUSED = "AD_PAUSED";
    public static final String RESUMED = "AD_RESUMED";
    public static final String ARCHIVED = "AD_ARCHIVED";
    public static final String REJECTED = "AD_CHANGE_REJECTED";
    private static final String TARGET = "AGAFARI_AD";

    private final AgafariAdRepository ads;
    private final AdminAuditService audit;
    private final JdbcTemplate jdbc;
    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final TransactionTemplate transaction;
    private final String bucket;
    private final String publicBaseUrl;
    private final Clock clock = Clock.systemUTC();

    public AgafariAdService(AgafariAdRepository ads, AdminAuditService audit, JdbcTemplate jdbc, S3Client s3Client, S3Presigner presigner,
                            PlatformTransactionManager transactionManager, @Value("${cloudflare.bucket}") String bucket,
                            @Value("${cloudflare.publicBaseUrl}") String publicBaseUrl) {
        this.ads = ads;
        this.audit = audit;
        this.jdbc = jdbc;
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.transaction = new TransactionTemplate(transactionManager);
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl;
    }

    // ------------------------------------------------------------------------------------------------------- reads

    public List<AdResponse> list() {
        Map<String, Long> served = jdbc.query("select ad_id, sum(impression_count) from ad_impression_daily group by ad_id",
                (rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        return ads.findAllByOrderByCreatedAtDesc().stream().map(a -> response(a, served.getOrDefault(a.getId(), 0L))).toList();
    }

    public AdImpressionsResponse impressions(String id) {
        ads.findById(id).orElseThrow(() -> new NotFoundException("Ad not found"));
        Long total = jdbc.queryForObject("select coalesce(sum(impression_count), 0) from ad_impression_daily where ad_id = ?", Long.class, id);
        List<AdImpressionsResponse.Day> days = jdbc.query("select view_date, sum(impression_count) from ad_impression_daily where ad_id = ? and view_date >= ? "
                + "group by view_date order by view_date", (rs, i) -> new AdImpressionsResponse.Day(rs.getObject(1, LocalDate.class), rs.getLong(2)),
                id, LocalDate.now(clock).minusDays(29));
        List<AdImpressionsResponse.Restaurant> restaurants = jdbc.query("select d.business_id, b.name, sum(d.impression_count) from ad_impression_daily d "
                + "join business b on b.id = d.business_id where d.ad_id = ? group by d.business_id, b.name order by 3 desc, 2",
                (rs, i) -> new AdImpressionsResponse.Restaurant(rs.getString(1), rs.getString(2), rs.getLong(3)), id);
        return new AdImpressionsResponse(id, total == null ? 0 : total, days, restaurants);
    }

    // ----------------------------------------------------------------------------------------------------- changes

    public AdResponse create(AdRequest request, String adminId, String adminEmail) {
        return audited(adminId, adminEmail, actor -> {
            AgafariAd.Content content = validate(request);
            AgafariAd saved = transaction.execute(status -> ads.saveAndFlush(AgafariAd.create(content, clock.instant(), adminId)));
            audit.success(CREATED, adminId, actor, TARGET, saved.getId(), describe(saved));
            return response(saved, 0);
        });
    }

    public AdResponse update(String id, AdRequest request, String adminId, String adminEmail) {
        return audited(adminId, adminEmail, actor -> {
            AgafariAd.Content content = validate(request);
            if (request.rowVersion() == null) throw new FieldValidationException(Map.of("rowVersion", "Reload and try again"));
            return change(id, request.rowVersion(), ad -> ad.apply(content, clock.instant(), adminId), UPDATED, adminId, actor);
        });
    }

    public AdResponse pause(String id, AdActionRequest request, String adminId, String adminEmail) {
        return transition(id, request, AgafariAd.ACTIVE, AgafariAd.PAUSED, "This ad isn't running", PAUSED, adminId, adminEmail);
    }

    public AdResponse resume(String id, AdActionRequest request, String adminId, String adminEmail) {
        return transition(id, request, AgafariAd.PAUSED, AgafariAd.ACTIVE, "This ad isn't paused", RESUMED, adminId, adminEmail);
    }

    public AdResponse archive(String id, AdActionRequest request, String adminId, String adminEmail) {
        return transition(id, request, null, AgafariAd.ARCHIVED, null, ARCHIVED, adminId, adminEmail);
    }

    private AdResponse transition(String id, AdActionRequest request, String from, String to, String wrongState, String action, String adminId, String adminEmail) {
        return audited(adminId, adminEmail, actor -> {
            if (request.rowVersion() == null) throw new FieldValidationException(Map.of("rowVersion", "Reload and try again"));
            return change(id, request.rowVersion(), ad -> {
                if (from != null && !from.equals(ad.getStatus())) throw new FieldValidationException(Map.of("status", wrongState));
                ad.changeStatus(to, clock.instant(), adminId);
            }, action, adminId, actor);
        });
    }

    // ------------------------------------------------------------------------------------------------------ images

    public PresignLogoResponse presignImage(String id, AdImageRequest request) {
        AgafariAd ad = ads.findById(id).orElseThrow(() -> new NotFoundException("Ad not found"));
        if (AgafariAd.ARCHIVED.equals(ad.getStatus())) throw new AdArchivedException();
        String key = ImagePolicy.objectKey(prefix(id), request.contentType()); // allowed image types only (S1-17)
        PutObjectRequest put = PutObjectRequest.builder().bucket(bucket).key(key).contentType(ImagePolicy.normalize(request.contentType())).build();
        String url = presigner.presignPutObject(PutObjectPresignRequest.builder().signatureDuration(Duration.ofMinutes(5)).putObjectRequest(put).build()).url().toString();
        return PresignLogoResponse.builder().objectKey(key).uploadUrl(url).expiresInSeconds(300).build();
    }

    public AdResponse confirmImage(String id, AdImageRequest request, String adminId, String adminEmail) {
        return audited(adminId, adminEmail, actor -> {
            Map<String, String> errors = new LinkedHashMap<>();
            String alt = request.imageAlt() == null ? null : request.imageAlt().trim();
            if (alt == null || alt.isEmpty() || alt.length() > 200) errors.put("imageAlt", "Describe the image (at most 200 characters)");
            if (request.rowVersion() == null) errors.put("rowVersion", "Reload and try again");
            if (!errors.isEmpty()) throw new FieldValidationException(errors);
            String key = request.objectKey() == null ? null : request.objectKey().trim();
            String ext = ImagePolicy.extensionOfKey(key, prefix(id));
            if (ext == null) throw new BadRequestException("objectKey does not match this ad's image upload");
            HeadObjectResponse head;
            try {
                head = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            } catch (Exception e) {
                throw new BadRequestException("Image not found in storage (upload not completed)");
            }
            String problem = ImagePolicy.problemWith(head, ext);
            if (problem != null) {
                deleteQuietly(id, key);
                throw new BadRequestException(problem);
            }
            String[] previous = new String[1];
            AdResponse response = change(id, request.rowVersion(), ad -> {
                previous[0] = ad.getImageKey();
                ad.changeImage(key, alt, clock.instant(), adminId);
            }, UPDATED, adminId, actor);
            if (previous[0] != null && !previous[0].equals(key)) deleteQuietly(id, previous[0]);
            return response;
        });
    }

    public AdResponse removeImage(String id, AdActionRequest request, String adminId, String adminEmail) {
        return audited(adminId, adminEmail, actor -> {
            if (request.rowVersion() == null) throw new FieldValidationException(Map.of("rowVersion", "Reload and try again"));
            String[] previous = new String[1];
            AdResponse response = change(id, request.rowVersion(), ad -> {
                previous[0] = ad.getImageKey();
                ad.changeImage(null, null, clock.instant(), adminId);
            }, UPDATED, adminId, actor);
            if (previous[0] != null) deleteQuietly(id, previous[0]);
            return response;
        });
    }

    public String publicImageUrl(String imageKey) {
        return LogoUrls.publicUrl(publicBaseUrl, imageKey);
    }

    // ----------------------------------------------------------------------------------------------------- helpers

    private interface Change {
        void apply(AgafariAd ad);
    }

    private AdResponse change(String id, int rowVersion, Change change, String action, String adminId, String actor) {
        AgafariAd saved = transaction.execute(status -> {
            AgafariAd ad = ads.findById(id).orElseThrow(() -> new NotFoundException("Ad not found"));
            if (AgafariAd.ARCHIVED.equals(ad.getStatus())) throw new AdArchivedException();
            if (!ad.getRowVersion().equals(rowVersion)) throw new AdVersionConflictException();
            change.apply(ad);
            AgafariAd result = ads.saveAndFlush(ad);
            audit.success(action, adminId, actor, TARGET, result.getId(), describe(result));
            return result;
        });
        return response(saved, served(saved.getId()));
    }

    private AdResponse audited(String adminId, String adminEmail, Function<String, AdResponse> work) {
        String actor = LogMasking.maskEmail(adminEmail);
        try {
            return work.apply(actor);
        } catch (OptimisticLockingFailureException e) {
            audit.failure(REJECTED, adminId, actor, "version_conflict");
            throw new AdVersionConflictException();
        } catch (FieldValidationException | BadRequestException e) {
            audit.failure(REJECTED, adminId, actor, "invalid");
            throw e;
        } catch (AdVersionConflictException e) {
            audit.failure(REJECTED, adminId, actor, "version_conflict");
            throw e;
        } catch (AdArchivedException e) {
            audit.failure(REJECTED, adminId, actor, "archived");
            throw e;
        } catch (NotFoundException e) {
            audit.failure(REJECTED, adminId, actor, "not_found");
            throw e;
        }
    }

    private AgafariAd.Content validate(AdRequest r) {
        Map<String, String> errors = new LinkedHashMap<>();
        AdContentRules.Text text = AdContentRules.check(r.title(), r.body(), r.titleAm(), r.bodyAm(), r.linkUrl(), errors); // shared with E4-M8
        ZoneId zone = AdSchedule.zone(r.timeZone(), errors);
        Instant start = null, end = null;
        if (r.startLocal() == null || r.startLocal().isBlank()) errors.put("startLocal", "Enter a start date and time");
        else start = AdSchedule.toInstant("startLocal", "startOffset", r.startLocal(), r.startOffset(), zone, errors);
        if (r.endLocal() != null && !r.endLocal().isBlank()) {
            end = AdSchedule.toInstant("endLocal", "endOffset", r.endLocal(), r.endOffset(), zone, errors);
            if (start != null && end != null && !end.isAfter(start)) errors.put("endLocal", "The end must be after the start");
        }
        if (!errors.isEmpty()) throw new FieldValidationException(errors);
        return new AgafariAd.Content(text.title(), text.body(), text.titleAm(), text.bodyAm(), text.linkUrl(), start, end, zone.getId());
    }

    private long served(String id) {
        Long n = jdbc.queryForObject("select coalesce(sum(impression_count), 0) from ad_impression_daily where ad_id = ?", Long.class, id);
        return n == null ? 0 : n;
    }

    private AdResponse response(AgafariAd a, long served) {
        ZoneId zone = ZoneId.of(a.getScheduleTimeZone());
        return new AdResponse(a.getId(), a.getTitle(), a.getBody(), a.getTitleAm(), a.getBodyAm(), a.getLinkUrl(), publicImageUrl(a.getImageKey()),
                a.getImageAlt(), a.getStartsAt(), a.getEndsAt(), a.getScheduleTimeZone(), AdSchedule.local(a.getStartsAt(), zone),
                AdSchedule.offset(a.getStartsAt(), zone), AdSchedule.local(a.getEndsAt(), zone), AdSchedule.offset(a.getEndsAt(), zone),
                a.getStatus(), served, a.getCreatedAt(), a.getUpdatedAt(), a.getRowVersion());
    }

    private static String describe(AgafariAd a) {
        return "status=" + a.getStatus() + " starts=" + a.getStartsAt() + " ends=" + a.getEndsAt() + " image=" + (a.getImageKey() != null);
    }

    private static String prefix(String id) {
        return "agafari-ad-asset/" + id;
    }

    private void deleteQuietly(String id, String key) {
        if (!key.startsWith(prefix(id) + "/")) return;
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception e) {
            log.warn("event=ad.image.delete_failed key={} errorClass={}", key, e.getClass().getSimpleName());
        }
    }
}
