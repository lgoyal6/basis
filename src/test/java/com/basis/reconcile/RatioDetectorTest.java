package com.basis.reconcile;

import static com.basis.support.Fixtures.qty;
import static org.assertj.core.api.Assertions.assertThat;

import com.basis.domain.Quantity;
import java.math.BigDecimal;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The arithmetic that decides whether a position break is a corporate action or a missing
 * trade.
 *
 * <p>Getting this wrong in either direction is expensive. Too permissive and every missing
 * purchase is confidently explained as a split, which sends someone looking in the wrong
 * place and teaches them to ignore the explanations. Too strict and the one thing the
 * product exists to notice goes unnoticed.
 */
class RatioDetectorTest {

    @ParameterizedTest(name = "broker {1} against computed {0} is {2} for {3}")
    @CsvSource({
        // The README's example, and the ordinary forward splits.
        "10,   40,  4, 1",
        "100,  200, 2, 1",
        "50,   350, 7, 1",
        // Reverse splits.
        "80,   10,  1, 8",
        "1000, 1,   1, 1000",
        // Two sided ratios issuers really do declare.
        "100,  150, 3, 2",
        "400,  500, 5, 4",
        "500,  700, 7, 5",
        // Fractional share counts reduce just as exactly.
        "10.5, 21,  2, 1",
        "0.25, 1,   4, 1",
    })
    @DisplayName("clean corporate action ratios are found exactly")
    void findsPlausibleRatios(String computed, String broker, long numerator, long denominator) {
        assertThat(RatioDetector.between(qty(computed), qty(broker)))
                .contains(new Ratio(numerator, denominator));
    }

    @ParameterizedTest(name = "broker {1} against computed {0} is not a corporate action")
    @CsvSource({
        // A fraction, but not one any issuer has declared. This is a missing trade.
        "100, 137",
        "100, 101",
        "7,   11",
        "13,  17",
        // Beyond the one sided limit.
        "1,    1001",
        "1001, 1",
        // Agreement is not a ratio.
        "100, 100",
        // Nothing to compare against.
        "0,   100",
        "100, 0",
    })
    @DisplayName("anything that is not the shape of a declared split is refused")
    void refusesImplausibleRatios(String computed, String broker) {
        assertThat(RatioDetector.between(qty(computed), qty(broker))).isEmpty();
    }

    @Test
    @DisplayName("the boundary is where it says it is")
    void boundariesAreExact() {
        assertThat(RatioDetector.between(qty("1"), qty("1000"))).contains(new Ratio(1000, 1));
        assertThat(RatioDetector.between(qty("1"), qty("1001"))).isEmpty();
        assertThat(RatioDetector.between(qty("9"), qty("10"))).contains(new Ratio(10, 9));
        assertThat(RatioDetector.between(qty("10"), qty("11"))).isEmpty();
    }

    @Test
    @DisplayName("a ratio and its inverse are found from either side")
    void isSymmetric() {
        assertThat(RatioDetector.between(qty("10"), qty("40"))).contains(new Ratio(4, 1));
        assertThat(RatioDetector.between(qty("40"), qty("10"))).contains(new Ratio(1, 4));
    }

    @Property(tries = 500)
    @Label("any position scaled by a declared ratio is detected as exactly that ratio")
    void anyPositionScaledByARatioIsDetected(
            @ForAll("declaredRatios") Ratio ratio,
            @ForAll @LongRange(min = 1, max = 100_000) long units) {
        // Held as a multiple of the denominator so the scaled count is exact and the test
        // is measuring the detector rather than its own rounding.
        Quantity computed = Quantity.of(BigDecimal.valueOf(units).multiply(BigDecimal.valueOf(ratio.denominator())));
        Quantity broker = Quantity.of(BigDecimal.valueOf(units).multiply(BigDecimal.valueOf(ratio.numerator())));

        Optional<Ratio> found = RatioDetector.between(computed, broker);

        if (ratio.numerator() == ratio.denominator()) {
            assertThat(found).as("a 1 for 1 ratio is agreement, not a break").isEmpty();
        } else {
            assertThat(found).contains(ratio);
        }
    }

    @Property(tries = 500)
    @Label("a position off by one share is never explained as a corporate action")
    void oneShareOutIsNeverASplit(@ForAll @LongRange(min = 11, max = 100_000) long held) {
        Quantity computed = Quantity.of(BigDecimal.valueOf(held));
        Quantity broker = Quantity.of(BigDecimal.valueOf(held + 1));

        assertThat(RatioDetector.between(computed, broker))
                .as("%s against %s is a missing trade, whatever fraction it reduces to", held, held + 1)
                .isEmpty();
    }

    @Provide
    Arbitrary<Ratio> declaredRatios() {
        Arbitrary<Ratio> forward = Arbitraries.longs().between(2, 20).map(n -> new Ratio(n, 1));
        Arbitrary<Ratio> reverse = Arbitraries.longs().between(2, 1000).map(d -> new Ratio(1, d));
        Arbitrary<Ratio> twoSided = Arbitraries.of(
                new Ratio(3, 2), new Ratio(5, 4), new Ratio(7, 5), new Ratio(9, 10), new Ratio(2, 3));
        return Arbitraries.oneOf(forward, reverse, twoSided);
    }
}
