package com.agafari.com.service;

import com.agafari.com.exception.BadRequestException;

import java.util.Locale;
import java.util.Set;

/** One currency per restaurant (AGF-S2-02). Owners may choose ETB or USD (D6); the default is ETB. */
public final class CurrencyPolicy {

    public static final String DEFAULT = "ETB";
    public static final Set<String> SUPPORTED = Set.of("ETB", "USD");

    private CurrencyPolicy() {
    }

    /** Currency to show; a missing value falls back to the entity default (ETB), never USD. */
    public static String displayCurrency(String currency) {
        return (currency == null || currency.isBlank()) ? DEFAULT : currency;
    }

    /** Normalizes an owner-supplied currency (" usd " → "USD"); 400 if it isn't supported. */
    public static String requireSupported(String currency) {
        String code = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED.contains(code)) throw new BadRequestException("Unsupported currency. Use ETB or USD.");
        return code;
    }
}
