package com.basis.reconcile;

/**
 * A whole number ratio in lowest terms, as in "4 for 1".
 *
 * @param numerator what the broker reports, per {@code denominator} the ledger computed
 */
public record Ratio(long numerator, long denominator) {

    public Ratio {
        if (numerator <= 0 || denominator <= 0) {
            throw new IllegalArgumentException("a ratio must be positive, was " + numerator + ":" + denominator);
        }
    }

    /** True when the broker holds more than the ledger, which is what an unapplied split looks like. */
    public boolean isIncrease() {
        return numerator > denominator;
    }

    @Override
    public String toString() {
        return numerator + " for " + denominator;
    }
}
