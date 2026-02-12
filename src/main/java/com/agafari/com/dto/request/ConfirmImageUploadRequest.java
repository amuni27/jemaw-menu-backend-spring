package com.agafari.com.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ConfirmImageUploadRequest {
    @NotBlank
    private String objectKey;
}
