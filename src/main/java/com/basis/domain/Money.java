package com.basis.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An exact cash amount, held as integer minor units of its currency.
 *
 * <p>There is no fractional cent. Every arithmetic operation here is exact or it
 * throws: a ledger that silently loses a half cent is a ledger that cannot be
 * reconciled against a broker.
 */
public record Money(long minorUnits, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money ofMinor(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    /**
     * Converts a major-unit amount exactly. Throws if the amount does not land on a
     * whole minor unit, because rounding here would be a silent basis error.
     */
    public static Money of(BigDecimal majorUnits, Currency currency) {
        Objects.requireNonNull(majorUnits, "majorUnits");
        Objects.requireNonNull(currency, "currency");
        int digits = minorUnitDigits(currency);
        BigDecimal scaled;
        try {
            scaled = majorUnits.setScale(digits, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new ArithmeticException(
                    "amount " + majorUnits.toPlainString() + " is not exact in " + currency.getCurrencyCode()
                            + " minor units (" + digits + " digits)");
        }
        return new Money(scaled.movePointRight(digits).longValueExact(), currency);
    }

    /** Converts a major-unit amount, rounding HALF_EVEN to the currency's minor unit. */
    public static Money round(BigDecimal majorUnits, Currency currency) {
        Objects.requireNonNull(majorUnits, "majorUnits");
        Objects.requireNonNull(currency, "currency");
        int digits = minorUnitDigits(currency);
        BigDecimal scaled = majorUnits.setScale(digits, RoundingMode.HALF_EVEN);
        return new Money(scaled.movePointRight(digits).longValueExact(), currency);
    }

    /** Currencies with no published fraction digits (XAU and friends) are treated as whole units. */
    private static int minorUnitDigits(Currency currency) {
        int digits = currency.getDefaultFractionDigits();
        return digits < 0 ? 0 : digits;
    }

    public BigDecimal toMajorUnits() {
        return BigDecimal.valueOf(minorUnits, minorUnitDigits(currency));
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    public Money abs() {
        return minorUnits < 0 ? negate() : this;
    }

    public boolean isZero() {
        return minorUnits == 0L;
    }

    public boolean isNegative() {
        return minorUnits < 0L;
    }

    public boolean isPositive() {
        return minorUnits > 0L;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minorUnits, other.minorUnits);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "currency mismatch: " + currency.getCurrencyCode() + " vs " + other.currency.getCurrencyCode());
        }
    }

    @Override
    public String toString() {
        return toMajorUnits().toPlainString() + " " + currency.getCurrencyCode();
    }
}
