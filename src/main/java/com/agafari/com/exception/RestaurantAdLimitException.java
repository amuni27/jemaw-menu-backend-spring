package com.agafari.com.exception;

/** AGF E4-M8 (Q-M8-5): the safety cap of non-archived ads per restaurant (not a plan limit). */
public class RestaurantAdLimitException extends RuntimeException {

    public static final String CODE = "RESTAURANT_AD_LIMIT";

    public RestaurantAdLimitException(int cap) {
        super("You can have at most " + cap + " ads that aren't archived. Archive one to add another.");
    }
}
