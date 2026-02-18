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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Duration;

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
        String currentBusinessId = currentUser.businessId();

        // ✅ only allow your own business
        if (!businessId.equals(currentBusinessId)) {
            throw new BadRequestException("Not allowed");
        }

        var business = businessRepo.findById(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found"));

        String ext = guessExt(req.getFileName(), req.getContentType());
        String objectKey = "business-logo-asset/" + businessId + "/main" + ext;

        // store ONLY key
        business.setLogoKey(objectKey); // create this column (logoKey) OR reuse imageUrl
        businessRepo.save(business);

        String uploadUrl = presignPut(objectKey, req.getContentType());

        return PresignLogoResponse.builder()
                .objectKey(objectKey)
                .uploadUrl(uploadUrl)
                .expiresInSeconds(300)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public BusinessResponse confirmLogo(String businessId, ConfirmImageUploadRequest req) {
        String currentBusinessId = currentUser.businessId();
        if (!businessId.equals(currentBusinessId)) {
            throw new BadRequestException("Not allowed");
        }

        var business = businessRepo.findById(businessId)
                .orElseThrow(() -> new NotFoundException("Business not found"));

        String key = business.getLogoKey();
        if (key == null) throw new BadRequestException("Business has no logo key");

        if (!key.equals(req.getObjectKey())) {
            throw new BadRequestException("objectKey does not match stored logo key");
        }

        // confirm exists in R2
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
        } catch (Exception e) {
            throw new BadRequestException("Logo not found in storage (upload not completed)");
        }

        // return business with public URL
        String publicUrl = publicBaseUrl.replaceAll("/$", "") + "/" + key.replaceAll("^/", "");
        return BusinessResponse.from(business, publicUrl);
    }

    private String presignPut(String objectKey, String contentType) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                // Optional: enforce content type
                .contentType(contentType)
                .build();

        PutObjectPresignRequest presignReq = PutObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(5))
                .putObjectRequest(put)
                .build();

        return r2Presigner.presignPutObject(presignReq).url().toString();
    }

    private String guessExt(String fileName, String contentType) {
        if (fileName != null && fileName.contains(".")) return fileName.substring(fileName.lastIndexOf("."));
        if ("image/png".equalsIgnoreCase(contentType)) return ".png";
        if ("image/webp".equalsIgnoreCase(contentType)) return ".webp";
        return ".jpg";
    }
}

