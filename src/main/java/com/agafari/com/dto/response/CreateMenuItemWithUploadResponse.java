package com.agafari.com.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateMenuItemWithUploadResponse {
    private MenuItemResponse item;
    private UploadInfo upload; // null if no image requested
}