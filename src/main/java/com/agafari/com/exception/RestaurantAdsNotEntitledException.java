package com.agafari.com.exception;

/** AGF E4-M8 (Q-M8-9): restaurant ads are off or the restaurant isn't on a current Model 3 plan: read and archive only. */
public class RestaurantAdsNotEntitledException extends RuntimeException {

    public static final String CODE = "RESTAURANT_ADS_NOT_ENTITLED";

    public RestaurantAdsNotEntitledException() {
        super("Your plan doesn't include restaurant ads. You can view and archive your ads.");
    }
}
