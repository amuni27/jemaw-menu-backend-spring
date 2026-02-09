package com.agafari.com.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CategoryResponse {

    private String id;
    private String menuId;
    private String name;
    private int sortOrder;
    private boolean isActive;
}

