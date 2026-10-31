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
    @Size(max = 255)
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

    /** AGF-S2-23: optional Amharic translation; blank clears (update), omitted keeps */
    @Size(max = 255)
    private String nameAm;

    /** AGF-S2-23: optional Amharic description; blank clears (update), omitted keeps */
    @Size(max = 255)
    private String descriptionAm;
}
