package com.agafari.com.service.impl;

import com.agafari.com.dto.request.ConfirmImageUploadRequest;
import com.agafari.com.dto.request.PresignLogoRequest;
import com.agafari.com.dto.response.BusinessResponse;
import com.agafari.com.dto.response.PresignLogoResponse;
import com.agafari.com.exception.BadRequestException;
import com.agafari.com.exception.NotFoundException;
import com.agafari.com.jpa.repository.BusinessRepository;
import com.agafari.com.security.CurrentUser;
import com.agafari.com.service.BusinessLogoService;
import com.agafari.com.service.ImagePolicy;
import com.agafari.com.service.LogoUrls;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessLogoServiceImpl implements BusinessLogoService {

    private final CurrentUser currentUser;
    private final BusinessRepository businessRepo;
    private final S3Client s3Client;
    private final S3Presigner r2Presigner;

    @Value("${cloudflare.bucket}")
    private String bucket;

    @Value("${cloudflare.publicBaseUrl}")
    private String publicBaseUrl;

    @Override
    @Transactional
    public PresignLogoResponse presignLogo(String businessId, PresignLogoRequest req) {
        currentUser.requireOwnBusiness(businessId);

        businessRepo.findById(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found"));

        // AGF-S1-17: allowed image types only; key from the type (file name ignored); saved at confirm
        String objectKey = ImagePolicy.objectKey(logoPrefix(businessId), req.getContentType());
        String uploadUrl = presignPut(objectKey, req.getContentType());

        return PresignLogoResponse.builder()
                .objectKey(objectKey)
                .uploadUrl(uploadUrl)
                .expiresInSeconds(300)
                .build();
    }

    @Override
    // Keep a cleared reference even though the request then fails with 400 (AGF-S1-17)
    @Transactional(noRollbackFor = BadRequestException.class)
    public BusinessResponse confirmLogo(String businessId, ConfirmImageUploadRequest req) {
        currentUser.requireOwnBusiness(businessId);

        var business = businessRepo.findById(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found"));

        String key = req.getObjectKey() == null ? null : req.getObjectKey().trim();
        String ext = ImagePolicy.extensionOfKey(key, logoPrefix(businessId));
        if (ext == null) throw new BadRequestException("objectKey does not match this business's logo upload");

        HeadObjectResponse head;
        try {
            head = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception e) {
            throw new BadRequestException("Logo not found in storage (upload not completed)");
        }

        // AGF-S1-17: invalid uploads are deleted and never referenced
        String problem = ImagePolicy.problemWith(head, ext);
        if (problem != null) {
            try {
                s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            } catch (Exception ignored) {
                // object stays orphaned under the business prefix; it is never referenced
            }
            if (key.equals(business.getLogoKey())) {
                business.setLogoKey(null);
                businessRepo.save(business);
            }
            throw new BadRequestException(problem);
        }

        String previousKey = business.getLogoKey();
        business.setLogoKey(key);
        businessRepo.save(business);

        // AGF-S2-01: a logo of another type has another key; the old object would be orphaned
        if (previousKey != null && !previousKey.equals(key)) deleteOwnLogoQuietly(businessId, previousKey);

        return BusinessResponse.from(business, LogoUrls.publicUrl(publicBaseUrl, key));
    }

    @Override
    @Transactional
    public BusinessResponse removeLogo(String businessId) {
        currentUser.requireOwnBusiness(businessId);

        var business = businessRepo.findById(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found"));

        String key = business.getLogoKey();
        if (key != null) {
            business.setLogoKey(null);
            businessRepo.save(business);
            deleteOwnLogoQuietly(businessId, key);
            log.info("event=business.logo.removed businessId={}", businessId);
        }
        return BusinessResponse.from(business, null);
    }

    /** Deletes a logo object, but only one under this business's own logo prefix; failures are logged, not thrown. */
    private void deleteOwnLogoQuietly(String businessId, String key) {
        if (!key.startsWith(logoPrefix(businessId) + "/")) return;
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception e) {
            log.warn("event=upload.delete_failed key={} errorClass={}", key, e.getClass().getSimpleName());
        }
    }

    private String presignPut(String objectKey, String contentType) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                // Signed: R2 rejects an upload whose Content-Type differs (AGF-S1-17)
                .contentType(ImagePolicy.normalize(contentType))
                .build();

        PutObjectPresignRequest presignReq = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(5))
                .putObjectRequest(put)
                .build();

        return r2Presigner.presignPutObject(presignReq).url().toString();
    }

    private static String logoPrefix(String businessId) {
        return "business-logo-asset/" + businessId;
    }
}

