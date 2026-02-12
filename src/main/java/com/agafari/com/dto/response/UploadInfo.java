package com.agafari.com.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UploadInfo {
    private String objectKey;
    private String uploadUrl;
    private int expiresInSeconds;
}