package com.agafari.com.service;


import com.agafari.com.dto.request.MenuCreateRequest;
import com.agafari.com.dto.request.MenuDuplicateRequest;
import com.agafari.com.dto.request.MenuUpdateRequest;
import com.agafari.com.dto.response.MenuDuplicateResponse;
import com.agafari.com.dto.response.MenuResponse;
import com.agafari.com.dto.response.MenuTypeResponse;

import java.util.List;

public interface MenuService {
    List<MenuResponse> listMyBusinessMenus();
    MenuResponse createMenu(MenuCreateRequest request);
    MenuResponse getMyBusinessMenu(String menuId);
    MenuResponse patchMenu(String menuId, MenuUpdateRequest request);
    void deleteMenuCascade(String menuId);

    /** AGF-S2-18: copies one of the business's menus, with its categories and items, into a new unpublished menu. */
    MenuDuplicateResponse duplicateMenu(String menuId, MenuDuplicateRequest request);
    List<MenuTypeResponse> getAllMenuTypes();
}
