package com.agafari.com.billing;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** AGF SUB-R3-02: a market comes only from the restaurant's stored country, with no guessing. */
class MarketTest {

    @Test
    void theApprovedCountriesMapToTheirMarket() {
        assertThat(Market.fromCountry("US")).contains(Market.US);
        assertThat(Market.fromCountry("ET")).contains(Market.ET);
    }

    @Test
    void anythingElseHasNoMarket() {
        for (String country : Arrays.asList("GB", "CA", "", " ", "us", "et", " US", "USA", null)) {
            assertThat(Market.fromCountry(country)).as(String.valueOf(country)).isEqualTo(Optional.empty());
        }
    }
}
