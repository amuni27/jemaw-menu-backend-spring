package com.agafari.com.dto.response;

import com.agafari.com.enums.MenuVisibility;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class MenuWithCategoriesResponse {
    private String id;
    private String name;
    private String description;
    private boolean isActive;
    private MenuVisibility visibility;

    private MenuTypeResponse menuType;
    /** The restaurant's currency (AGF-S2-02) */
    private String currency;
    private List<CategoryWithItemsResponse> categories;

    /** AGF-S2-23: Amharic translation, or null */
    private String nameAm;
}
