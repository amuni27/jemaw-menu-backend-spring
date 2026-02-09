package com.agafari.com.dto.request;

import lombok.Data;

@Data
public class CategoryUpdateRequest {

    private String name;
    private Integer sortOrder;
    private Boolean isActive;
}
