package com.agafari.com.service.impl;

import com.agafari.com.service.R2StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;

@Service
@RequiredArgsConstructor
public class R2StorageServiceImpl implements R2StorageService {

    private final S3Client s3Client;

    @Value("${cloudflare.bucket}")
    private String bucket;


    public String upload(String name, MultipartFile file) throws IOException {
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(name)
                .contentType(file.getContentType())
                .build();

        s3Client.putObject(req, RequestBody.fromBytes(file.getBytes()));

        return "Successfully Uploaded: " + name;
    }

    public byte[] download(String name) {
        GetObjectRequest req = GetObjectRequest.builder()
                .bucket(bucket)
                .key(name)
                .build();

        return s3Client.getObjectAsBytes(req).asByteArray();
    }
}
