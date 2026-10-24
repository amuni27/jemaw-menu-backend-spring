package com.agafari.com.service;

import com.agafari.com.enums.MenuItemStatus;
import com.agafari.com.enums.MenuVisibility;

import java.util.Set;

/**
 * Single definition of what guests may see (PO-approved rules R1–R5).
 * An item is public only if its menu is active and PUBLIC, its category is active,
 * and its status is AVAILABLE or OUT_OF_STOCK (shown as Sold Out).
 */
public final class PublicVisibilityPolicy {

    public static final MenuVisibility PUBLIC_MENU_VISIBILITY = MenuVisibility.PUBLIC;

    public static final Set<MenuItemStatus> PUBLIC_ITEM_STATUSES =
            Set.of(MenuItemStatus.AVAILABLE, MenuItemStatus.OUT_OF_STOCK);

    private PublicVisibilityPolicy() {
    }
}
