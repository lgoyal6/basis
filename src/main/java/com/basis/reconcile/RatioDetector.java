package com.basis.reconcile;

import com.basis.domain.Quantity;
import java.math.BigInteger;
import java.util.Optional;

/**
 * Decides whether two share counts differ by a clean whole number ratio.
 *
 * <p>This is the arithmetic behind the product's headline: a broker reporting 40 shares
 * where the ledger computed 10 is not "30 shares missing", it is a 4 for 1 ratio, and a
 * 4 for 1 ratio on a security almost always means a split nobody applied.
 *
 * <p>Exact, with no tolerance anywhere. Quantities are {@link Quantity#SCALE} decimal
 * places, so both sides scale to whole numbers, and the ratio between two whole numbers
 * reduced by their greatest common divisor either is a small fraction or is not. Comparing
 * a computed ratio against a rounded target would make the answer depend on how close is
 * close enough, and on a position of ten million shares that question has no good answer.
 */
public final class RatioDetector {

    /**
     * How large a one sided ratio may be: 20 for 1 forward, 1 for 1000 reverse.
     *
     * <p>Reverse splits of stock on its way out really do reach 1 for 1000, and a forward
     * split rarely passes 20 for 1.
     */
    private static final BigInteger MAX_ONE_SIDED = BigInteger.valueOf(1000);

    /**
     * How large a two sided ratio may be, as in 3 for 2 or 5 for 4.
     *
     * <p>Kept small on purpose, and this is the whole difficulty of the detector. Every
     * pair of share counts reduces to some fraction, so "is it a ratio" is not a question
     * with a useful answer; "is it a ratio an issuer would announce" is. 137 for 100 is a
     * perfectly good fraction and no company has ever declared it. Letting it through
     * would attach a confident corporate action explanation to what is really a missing
     * trade, and send someone looking in the wrong place.
     */
    private static final BigInteger MAX_TWO_SIDED = BigInteger.valueOf(10);

    private RatioDetector() {
    }

    /**
     * The ratio of {@code broker} to {@code computed}, in lowest terms, when both sides
     * are small enough for it to mean something.
     *
     * @return empty when either side is zero or negative, when the two agree, or when the
     *     reduced fraction is too large to be a corporate action
     */
    public static Optional<Ratio> between(Quantity computed, Quantity broker) {
        if (!computed.isPositive() || !broker.isPositive() || computed.equals(broker)) {
            return Optional.empty();
        }

        BigInteger computedUnits = wholeUnits(computed);
        BigInteger brokerUnits = wholeUnits(broker);
        BigInteger divisor = brokerUnits.gcd(computedUnits);

        BigInteger numerator = brokerUnits.divide(divisor);
        BigInteger denominator = computedUnits.divide(divisor);
        if (!isPlausibleCorporateAction(numerator, denominator)) {
            return Optional.empty();
        }
        return Optional.of(new Ratio(numerator.longValueExact(), denominator.longValueExact()));
    }

    /**
     * Whether a reduced fraction is the shape of a ratio an issuer would actually declare:
     * n for 1, or 1 for n, or a small fraction like 3 for 2.
     */
    private static boolean isPlausibleCorporateAction(BigInteger numerator, BigInteger denominator) {
        if (denominator.equals(BigInteger.ONE)) {
            return numerator.compareTo(MAX_ONE_SIDED) <= 0;
        }
        if (numerator.equals(BigInteger.ONE)) {
            return denominator.compareTo(MAX_ONE_SIDED) <= 0;
        }
        return numerator.compareTo(MAX_TWO_SIDED) <= 0 && denominator.compareTo(MAX_TWO_SIDED) <= 0;
    }

    /** Scale 8 decimals become whole numbers, so the ratio can be reduced exactly. */
    private static BigInteger wholeUnits(Quantity quantity) {
        return quantity.value().movePointRight(Quantity.SCALE).toBigIntegerExact();
    }
}
