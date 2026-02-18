package com.agafari.com.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PresignLogoResponse {
    private String uploadUrl;
    private String objectKey;
    private Integer expiresInSeconds;
}
