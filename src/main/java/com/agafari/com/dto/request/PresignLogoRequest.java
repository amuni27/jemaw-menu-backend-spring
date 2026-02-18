package com.agafari.com.dto.request;


import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PresignLogoRequest {
    @NotBlank
    private String fileName;

    @NotBlank
    private String contentType;
}
