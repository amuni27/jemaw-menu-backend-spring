package com.agafari.com.service;


import com.agafari.com.dto.response.MenuItemPublicResponse;
import com.agafari.com.dto.response.PublicBusinessMenuResponse;

public interface PublicMenuService {
    PublicBusinessMenuResponse getBusinessMenu(String businessId);

    /** Guest-visible item of the given business; NotFoundException otherwise. */
    MenuItemPublicResponse getPublicItem(String businessId, String itemId);

    /** Guest-visible item of any business (legacy endpoint); NotFoundException otherwise. */
    MenuItemPublicResponse getPublicItem(String itemId);
}
