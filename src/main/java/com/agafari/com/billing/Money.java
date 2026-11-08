package com.agafari.com.billing;

import java.math.BigDecimal;

/** AGF SUB-R3-01 (BD-8): an exact amount in minor units. Never built from a floating value; currencies never mix. */
public record Money(long minor, Currency currency) {

    public Money {
        if (currency == null) throw new IllegalArgumentException("A currency is required");
    }

    /** Whole minor units only: a fractional or exponent form, such as 12.0 or 1E+2, is refused. */
    public static Money ofMinor(BigDecimal minor, Currency currency) {
        if (minor == null || minor.scale() != 0) throw new IllegalArgumentException("An amount must be a whole number of minor units");
        try {
            return new Money(minor.longValueExact(), currency);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("The amount is out of range", e);
        }
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public int compareAmount(Money other) {
        requireSameCurrency(other);
        return Long.compare(minor, other.minor);
    }

    private void requireSameCurrency(Money other) {
        if (other.currency != currency) throw new IllegalArgumentException("Currencies can't be mixed: " + currency + " and " + other.currency);
    }
}
