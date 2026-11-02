package com.agafari.com.dto.request;

/** AGF E4-M8: the Super Admin suspends ({@code reason}, required) or reinstates ({@code note}, optional) a restaurant ad. */
public record RestaurantAdModerationRequest(String reason, String note, Integer rowVersion) {
}
