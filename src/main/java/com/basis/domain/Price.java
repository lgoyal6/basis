package com.basis.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * A per-unit price in a currency, canonicalised to scale {@value #SCALE}.
 *
 * <p>Six decimal places because a per-unit cost basis is not a cash amount: spreading
 * a whole-cent total across a fractional share position routinely yields a unit cost
 * finer than a cent, and truncating it to two would leak basis on every disposal.
 */
public record Price(BigDecimal value, Currency currency) implements Comparable<Price> {

    public static final int SCALE = 6;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    public Price {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(currency, "currency");
        value = value.setScale(SCALE, ROUNDING);
    }

    public static Price of(BigDecimal value, Currency currency) {
        return new Price(value, currency);
    }

    public static Price of(String value, Currency currency) {
        return new Price(new BigDecimal(value), currency);
    }

    public static Price of(long value, Currency currency) {
        return new Price(BigDecimal.valueOf(value), currency);
    }

    public boolean isNegative() {
        return value.signum() < 0;
    }

    @Override
    public int compareTo(Price other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "cannot compare prices in " + currency.getCurrencyCode()
                            + " and " + other.currency.getCurrencyCode());
        }
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.stripTrailingZeros().toPlainString() + " " + currency.getCurrencyCode();
    }
}
