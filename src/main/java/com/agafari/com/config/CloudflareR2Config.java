package com.agafari.com.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
public class CloudflareR2Config {

    @Value("${cloudflare.accessKey}")
    private String accessKey;

    @Value("${cloudflare.secretKey}")
    private String secretKey;

    @Value("${cloudflare.endpoint}")
    private String endpoint;

    @Value("${cloudflare.bucket}")
    private String bucket;

    @Value("${cloudflare.publicBaseUrl}")
    private String publicBaseUrl;

    private StaticCredentialsProvider creds() {
        requireConfigured();
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);
        return StaticCredentialsProvider.create(credentials);
    }

    /**
     * ✅ S3Client for HEAD/GET/DELETE etc.
     */
    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.US_EAST_1) // any region works for R2, but required by SDK
                .credentialsProvider(creds())
                .endpointOverride(URI.create(endpoint))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true) // ✅ IMPORTANT for R2
                        .build())
                .build();
    }

    /**
     * ✅ REQUIRED for presigned PUT URLs (uploadUrl)
     */
    @Bean
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .region(Region.US_EAST_1)
                .credentialsProvider(creds())
                .endpointOverride(URI.create(endpoint))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true) // ✅ IMPORTANT for R2
                        .build())
                .build();
    }

    /**
     * Fail fast at startup when an R2 setting is present but blank.
     * Missing settings already fail placeholder resolution. Messages never include values.
     */
    private void requireConfigured() {
        requireText(accessKey, "cloudflare.accessKey", "CLOUDFLARE_R2_ACCESS_KEY");
        requireText(secretKey, "cloudflare.secretKey", "CLOUDFLARE_R2_SECRET_KEY");
        requireText(endpoint, "cloudflare.endpoint", "CLOUDFLARE_R2_ENDPOINT");
        requireText(bucket, "cloudflare.bucket", "CLOUDFLARE_R2_BUCKET");
        requireText(publicBaseUrl, "cloudflare.publicBaseUrl", "CLOUDFLARE_R2_PUBLIC_BASE_URL");
    }

    private static void requireText(String value, String property, String envVar) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must not be blank; set the " + envVar + " environment variable");
        }
    }
}
