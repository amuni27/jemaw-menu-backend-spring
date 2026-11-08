package com.agafari.com.billing;

/** AGF SUB-R3-01: the approved markets and the currency of each (TA §3.2). */
public enum Market {
    US(Currency.USD), ET(Currency.ETB);

    private final Currency currency;

    Market(Currency currency) {
        this.currency = currency;
    }

    public Currency currency() {
        return currency;
    }
}
