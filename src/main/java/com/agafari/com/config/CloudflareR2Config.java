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

    private StaticCredentialsProvider creds() {
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
}
