package com.agafari.com.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CategoryCreateRequest {

    @NotBlank
    private String name;

    private Integer sortOrder;

    private Boolean isActive;
}

