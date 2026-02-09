package com.agafari.com.dto.request;

import com.agafari.com.enums.MenuVisibility;
import lombok.Data;

@Data
public class MenuUpdateRequest {
    private String name;
    private String description;
    private Boolean isActive;
    private MenuVisibility visibility;
}
