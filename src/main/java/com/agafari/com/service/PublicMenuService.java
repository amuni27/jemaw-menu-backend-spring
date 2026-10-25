package com.agafari.com.service;


import com.agafari.com.dto.response.MenuItemPublicResponse;
import com.agafari.com.dto.response.PublicBusinessMenuResponse;

public interface PublicMenuService {
    PublicBusinessMenuResponse getBusinessMenu(String businessId);

    /** AGF-S2-17: the owner's own menu as guests would see it once published; NotFoundException for other menus. */
    PublicBusinessMenuResponse getOwnerPreview(String businessId, String menuId);

    /** Guest-visible item of the given business; NotFoundException otherwise. */
    MenuItemPublicResponse getPublicItem(String businessId, String itemId);

    /** Guest-visible item of any business (legacy endpoint); NotFoundException otherwise. */
    MenuItemPublicResponse getPublicItem(String itemId);
}
