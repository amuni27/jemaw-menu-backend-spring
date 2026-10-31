package com.agafari.com.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CategoryWithItemsResponse {
    private String id;
    private String menuId;
    private String name;
    private int sortOrder;
    private boolean isActive;
    private List<MenuItemPublicResponse> items;

    /** AGF-S2-23: Amharic translation, or null */
    private String nameAm;

    /** AGF-S2-36: the category's active special while it discounts at least one dish here; otherwise absent */
    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    private CategorySpecialPublicResponse special;
}
