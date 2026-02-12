package com.agafari.com.dto.response;

import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.enums.SpiceLevel;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class MenuItemPublicResponse {
    private String id;
    private String name;
    private String description;
    private BigDecimal price;
    private String imageUrl;

    private Integer calories;
    private boolean isFeatured;
    private Integer prepTimeMinutes;

    private SpiceLevel spiceLevel;
    private MenuItemStatus status;

    private int sortOrder;

    private List<String> ingredients;
    private List<String> allergens;
    private List<String> tags;
}
