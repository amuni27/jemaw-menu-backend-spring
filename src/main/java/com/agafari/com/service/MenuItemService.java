package com.agafari.com.service;

import com.agafari.com.dto.request.ConfirmImageUploadRequest;
import com.agafari.com.dto.request.MenuItemCreateRequest;
import com.agafari.com.dto.request.MenuItemStatusUpdateRequest;
import com.agafari.com.dto.request.MenuItemUpdateRequest;
import com.agafari.com.dto.response.CreateMenuItemWithUploadResponse;
import com.agafari.com.dto.response.MenuItemResponse;
import com.agafari.com.enums.MenuItemStatus;

import java.util.List;

public interface MenuItemService {
    CreateMenuItemWithUploadResponse createWithImageUpload(String menuId, MenuItemCreateRequest req);
    MenuItemResponse confirmImageUpload(String itemId, ConfirmImageUploadRequest req);
    CreateMenuItemWithUploadResponse presignImageUpload(String itemId);
    List<MenuItemResponse> list(String menuId, String categoryId, MenuItemStatus status, String search);
//    MenuItemResponse create(String menuId, MenuItemCreateRequest req);
    MenuItemResponse get(String itemId);
    MenuItemResponse update(String itemId, MenuItemUpdateRequest req);
    MenuItemResponse updateStatus(String itemId, MenuItemStatusUpdateRequest req);
    void delete(String itemId);
    MenuItemResponse getById(String itemId);
}
