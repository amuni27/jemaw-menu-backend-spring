package com.agafari.com.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CategoryCreateRequest {

    @NotBlank
    @Size(max = 255)
    private String name;

    private Integer sortOrder;

    private Boolean isActive;
}

