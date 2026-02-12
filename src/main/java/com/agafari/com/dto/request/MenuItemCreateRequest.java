package com.agafari.com.dto.request;


import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.enums.SpiceLevel;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class MenuItemCreateRequest {

    @NotBlank
    private String name;

    @NotNull
    @DecimalMin("0.00")
    private BigDecimal price;

    private String imageUrl;

    @NotBlank
    private String categoryId;

    @NotNull
    @Size(min = 1)
    private List<@NotBlank String> ingredients;

    private String description;
    private ImageMeta image;

    @Min(0)
    private Integer calories;

    private List<String> allergens;
    private List<String> tags;

    private Boolean isFeatured;

    @Min(0)
    private Integer prepTimeMinutes;

    private SpiceLevel spiceLevel;

    private MenuItemStatus status;
}

