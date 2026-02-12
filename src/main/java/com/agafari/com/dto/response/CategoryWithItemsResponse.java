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
}
