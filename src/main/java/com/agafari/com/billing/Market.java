package com.agafari.com.billing;

import java.util.Arrays;
import java.util.Optional;

/** AGF SUB-R3-01: the approved markets and the currency of each (TA §3.2). */
public enum Market {
    US(Currency.USD), ET(Currency.ETB);

    private final Currency currency;

    Market(Currency currency) {
        this.currency = currency;
    }

    /** AGF SUB-R3-02: the market of a restaurant's stored country; empty if it isn't exactly a market code. */
    public static Optional<Market> fromCountry(String country) {
        return Arrays.stream(values()).filter(m -> m.name().equals(country)).findFirst();
    }

    public Currency currency() {
        return currency;
    }
}
