package com.agafari.com.service;

import com.agafari.com.dto.request.ConfirmImageUploadRequest;
import com.agafari.com.dto.request.ItemReorderRequest;
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
    CreateMenuItemWithUploadResponse presignImageUpload(String itemId, String contentType);
    List<MenuItemResponse> list(String menuId, String categoryId, MenuItemStatus status, String search);
//    MenuItemResponse create(String menuId, MenuItemCreateRequest req);
    MenuItemResponse get(String itemId);
    MenuItemResponse update(String itemId, MenuItemUpdateRequest req);

    /** AGF-S2-08: saves the order of every item in a category */
    void reorder(String categoryId, ItemReorderRequest req);
    MenuItemResponse updateStatus(String itemId, MenuItemStatusUpdateRequest req);

    /** AGF-S2-19: every OUT_OF_STOCK item of the owner's menu becomes AVAILABLE; returns how many changed. */
    int markAllAvailable(String menuId);
    void delete(String itemId);
}
