package com.agafari.com.billing;

/** AGF SUB-R3-01 (Migration §6): whether every paid model enabled now has an active price in this market. */
public interface PriceAvailability {
    boolean availableIn(Market market);
}
