package com.agafari.com.dto.request;

import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.enums.SpiceLevel;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class MenuItemUpdateRequest {
    @Size(min = 1, max = 255)
    private String name;
    @DecimalMin("0.00")
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
    /** AGF-S2-30: true removes a saved prep time. A missing or null prepTimeMinutes still means "unchanged". */
    private Boolean clearPrepTime;
    /** AGF-S2-30: true removes a saved spice level. A missing or null spiceLevel still means "unchanged". */
    private Boolean clearSpiceLevel;
    private MenuItemStatus status;
}

