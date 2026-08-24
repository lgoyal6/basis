package com.basis.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A quantity of a commodity: shares, fund units, or units of a currency.
 *
 * <p>Canonicalised to scale {@value #SCALE} with HALF_EVEN on construction, so that
 * fractional shares are representable and so that {@code equals} means what a reader
 * expects. Without canonicalisation {@code 10} and {@code 10.00000000} would be
 * unequal records, and the position identity invariant would fail on formatting alone.
 */
public record Quantity(BigDecimal value) implements Comparable<Quantity> {

    public static final int SCALE = 8;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    public static final Quantity ZERO = new Quantity(BigDecimal.ZERO);

    public Quantity {
        Objects.requireNonNull(value, "value");
        value = value.setScale(SCALE, ROUNDING);
    }

    public static Quantity of(BigDecimal value) {
        return new Quantity(value);
    }

    public static Quantity of(long value) {
        return new Quantity(BigDecimal.valueOf(value));
    }

    public static Quantity of(String value) {
        return new Quantity(new BigDecimal(value));
    }

    public Quantity plus(Quantity other) {
        return new Quantity(value.add(other.value));
    }

    public Quantity minus(Quantity other) {
        return new Quantity(value.subtract(other.value));
    }

    public Quantity negate() {
        return new Quantity(value.negate());
    }

    public Quantity abs() {
        return new Quantity(value.abs());
    }

    /** Exact product with a price, at full precision. Rounding to money happens once, in {@link Posting}. */
    public BigDecimal multiplyBy(Price price) {
        return value.multiply(price.value());
    }

    public Quantity multiplyBy(BigDecimal factor) {
        return new Quantity(value.multiply(factor));
    }

    public Quantity divideBy(BigDecimal divisor) {
        return new Quantity(value.divide(divisor, SCALE, ROUNDING));
    }

    /** Unit cost implied by spreading {@code total} across this quantity. */
    public BigDecimal unitShareOf(BigDecimal total) {
        return total.divide(value, MathContext.DECIMAL128);
    }

    public boolean isZero() {
        return value.signum() == 0;
    }

    public boolean isPositive() {
        return value.signum() > 0;
    }

    public boolean isNegative() {
        return value.signum() < 0;
    }

    public int signum() {
        return value.signum();
    }

    @Override
    public int compareTo(Quantity other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.stripTrailingZeros().toPlainString();
    }
}
