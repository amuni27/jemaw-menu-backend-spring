package com.agafari.com.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CategoryUpdateRequest {

    @Size(min = 1, max = 255)
    private String name;
    private Integer sortOrder;
    private Boolean isActive;
}
