package com.agafari.com.dto.response;


import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.enums.SpiceLevel;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Data
@Builder
public class MenuItemResponse {
    private String id;
    private String menuId;
    private String categoryId;

    private String name;
    private String description;

    private BigDecimal price;
    private String imageUrl;

    private List<String> ingredients;
    private List<String> allergens;
    private List<String> tags;

    private Integer calories;
    private boolean isFeatured;
    private Integer prepTimeMinutes;
    private SpiceLevel spiceLevel;
    private MenuItemStatus status;

    private int sortOrder;

    private Instant createdAt;
    private Instant updatedAt;
}

