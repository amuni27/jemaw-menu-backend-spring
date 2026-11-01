package com.agafari.com.enums;

/** AGF E4-M2: the four monetization models (R2, R4–R6). Model 1 belongs to Setting 1; Models 2–4 to Setting 2. */
public enum MonetizationModel {
    /** Free trial, then a paid subscription. */
    MODEL_1,
    /** Free, with Agafari-managed ads. */
    MODEL_2,
    /** Paid, with restaurant-managed ads on that restaurant's menus only. */
    MODEL_3,
    /** Lower-cost paid plan, no ads. */
    MODEL_4
}
