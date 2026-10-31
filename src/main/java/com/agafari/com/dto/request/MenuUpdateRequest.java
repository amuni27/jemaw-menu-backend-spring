package com.agafari.com.dto.request;

import com.agafari.com.enums.MenuVisibility;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MenuUpdateRequest {
    @Size(min = 1, max = 255)
    private String name;
    private String description;
    private Boolean isActive;
    private MenuVisibility visibility;

    /** Optional: change the menu type (AGF-S1-14); must reference an existing type. */
    private String menuTypeId;

    /** AGF-S2-23: optional Amharic translation; blank clears (update), omitted keeps */
    @Size(max = 255)
    private String nameAm;
}
