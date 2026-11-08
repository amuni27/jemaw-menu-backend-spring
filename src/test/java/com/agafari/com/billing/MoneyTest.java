package com.agafari.com.billing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AGF SUB-R3-01 (BD-8). Amounts are fixtures, not prices. */
class MoneyTest {

    private static final Money USD_FIXTURE = new Money(1000, Currency.USD);
    private static final Money ETB_FIXTURE = new Money(1000, Currency.ETB);

    @Test
    void aCurrencyIsRequired() {
        assertThatThrownBy(() -> new Money(1, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalAmountsInDifferentCurrenciesAreNotEqual() {
        assertThat(USD_FIXTURE).isNotEqualTo(ETB_FIXTURE);
        assertThat(USD_FIXTURE).isEqualTo(new Money(1000, Currency.USD));
        assertThat(USD_FIXTURE.hashCode()).isEqualTo(new Money(1000, Currency.USD).hashCode());
    }

    @Test
    void mixedCurrenciesAreRefused() {
        assertThatThrownBy(() -> USD_FIXTURE.plus(ETB_FIXTURE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> USD_FIXTURE.minus(ETB_FIXTURE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> USD_FIXTURE.compareAmount(ETB_FIXTURE)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameCurrencyArithmeticIsExact() {
        assertThat(USD_FIXTURE.plus(new Money(1, Currency.USD))).isEqualTo(new Money(1001, Currency.USD));
        assertThat(USD_FIXTURE.minus(new Money(1, Currency.USD))).isEqualTo(new Money(999, Currency.USD));
        assertThat(USD_FIXTURE.compareAmount(new Money(1, Currency.USD))).isPositive();
    }

    @Test
    void overflowIsRefusedNotWrapped() {
        Money max = new Money(Long.MAX_VALUE, Currency.USD);
        assertThatThrownBy(() -> max.plus(new Money(1, Currency.USD))).isInstanceOf(ArithmeticException.class);
        Money min = new Money(Long.MIN_VALUE, Currency.USD);
        assertThatThrownBy(() -> min.minus(new Money(1, Currency.USD))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void wholeMinorUnitsAreAccepted() {
        assertThat(Money.ofMinor(new BigDecimal("1250"), Currency.ETB)).isEqualTo(new Money(1250, Currency.ETB));
    }

    @Test
    void floatingInputIsRefused() {
        for (String s : new String[]{"12.5", "12.0", "1E+2", "0.1"}) {
            assertThatThrownBy(() -> Money.ofMinor(new BigDecimal(s), Currency.USD)).as(s).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anAmountOutsideLongIsRefused() {
        assertThatThrownBy(() -> Money.ofMinor(new BigDecimal("9223372036854775808"), Currency.USD)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNullAmountIsRefused() {
        assertThatThrownBy(() -> Money.ofMinor(null, Currency.USD)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void marketsCarryTheirApprovedCurrency() {
        assertThat(Market.US.currency()).isEqualTo(Currency.USD);
        assertThat(Market.ET.currency()).isEqualTo(Currency.ETB);
    }

    @Test
    void moneyHasNoFloatingFactory() {
        assertThat(java.util.Arrays.stream(Money.class.getDeclaredMethods()).flatMap(m -> java.util.Arrays.stream(m.getParameterTypes())))
                .doesNotContain(double.class, float.class, Double.class, Float.class);
        assertThat(java.util.Arrays.stream(Money.class.getDeclaredConstructors()).flatMap(c -> java.util.Arrays.stream(c.getParameterTypes())))
                .doesNotContain(double.class, float.class, Double.class, Float.class);
    }
}
