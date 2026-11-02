package com.agafari.com.dto.request;

/**
 * AGF E4-M8: a restaurant ad's content and schedule. The schedule is a local date and time in the restaurant's own time
 * zone (Q-M8-7); {@code startOffset}/{@code endOffset} pick the offset of a local time that occurs twice. No time zone and
 * no restaurant here: both come from the signed-in owner's restaurant. {@code rowVersion} is required for updates.
 */
public record RestaurantAdRequest(String title, String body, String titleAm, String bodyAm, String linkUrl, String startLocal, String endLocal,
                                  String startOffset, String endOffset, Integer rowVersion) {
}
