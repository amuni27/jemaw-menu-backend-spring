package com.agafari.com.dto.request;

import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.enums.SpiceLevel;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class MenuItemUpdateRequest {
    private String name;
    private BigDecimal price;
    private String imageUrl;
    private String categoryId;
    private List<String> ingredients;
    private String description;
    @Min(0) private Integer calories;
    private List<String> allergens;
    private List<String> tags;
    private Boolean isFeatured;
    @Min(0) private Integer prepTimeMinutes;
    private SpiceLevel spiceLevel;
    private MenuItemStatus status;
}

