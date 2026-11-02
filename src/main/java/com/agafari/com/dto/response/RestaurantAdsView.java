package com.agafari.com.dto.response;

import java.util.List;

/**
 * AGF E4-M8: the owner's ads. {@code canManage}: the restaurant ads flag is on and the restaurant's entitlements include the
 * restaurant ad manager (current Model 3); otherwise the owner may only read and archive (Q-M8-9). {@code cap}: the safety
 * cap of non-archived ads (Q-M8-5, not a plan limit). {@code timeZone}: the restaurant's zone used for schedules.
 */
public record RestaurantAdsView(boolean canManage, int cap, long nonArchivedCount, String timeZone, List<RestaurantAdResponse> ads) {
}
