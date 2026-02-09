package com.agafari.com.service;


import com.agafari.com.dto.request.MenuCreateRequest;
import com.agafari.com.dto.request.MenuUpdateRequest;
import com.agafari.com.dto.response.MenuResponse;
import com.agafari.com.dto.response.MenuTypeResponse;

import java.util.List;

public interface MenuService {
    List<MenuResponse> listMyBusinessMenus();
    MenuResponse createMenu(MenuCreateRequest request);
    MenuResponse getMyBusinessMenu(String menuId);
    MenuResponse patchMenu(String menuId, MenuUpdateRequest request);
    void deleteMenuCascade(String menuId);
    List<MenuTypeResponse> getAllMenuTypes();
}
