package com.agafari.com.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** AGF-S2-36 (D-CS-6): the price of a dish under a category special. */
public final class SpecialPricing {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private SpecialPricing() {
    }

    /**
     * The regular price less {@code percent} %, rounded half-up to 2 decimals; null when there is nothing to discount
     * (no or zero price) or the discount rounds back to the regular price.
     */
    public static BigDecimal categoryPrice(BigDecimal price, int percent) {
        if (price == null || price.signum() <= 0) return null;
        BigDecimal discounted = price.multiply(BigDecimal.valueOf(100L - percent)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        return discounted.compareTo(price) < 0 ? discounted : null;
    }
}
