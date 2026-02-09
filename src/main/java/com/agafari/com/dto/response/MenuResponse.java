package com.agafari.com.dto.response;


import com.agafari.com.enums.MenuVisibility;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class MenuResponse {
    private String id;
    private String businessId;

    private String menuTypeId;
    private String menuTypeName;

    private String name;
    private String description;

    private boolean isActive;
    private MenuVisibility visibility;

    private String currency; // from business

    private Instant createdAt;
    private Instant updatedAt;
}

