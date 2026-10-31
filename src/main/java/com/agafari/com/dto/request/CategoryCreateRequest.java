package com.agafari.com.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CategoryCreateRequest {

    @NotBlank
    @Size(max = 255)
    private String name;

    private Integer sortOrder;

    private Boolean isActive;

    /** AGF-S2-23: optional Amharic translation; blank clears (update), omitted keeps */
    @Size(max = 255)
    private String nameAm;
}
