package com.basis.support;

import com.basis.domain.LotSelectionMethod;
import com.basis.support.GeneratedHistory.Intent;
import com.basis.support.GeneratedHistory.Kind;
import java.math.BigDecimal;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/**
 * Generators for {@link Intent}. Shared, so the in memory invariants and the replay
 * determinism property explore the same shapes rather than two hand kept lists that
 * drift apart.
 */
public final class Intents {

    private Intents() {
    }

    /** Every kind of step, which is what the invariants are asserted over. */
    public static Arbitrary<List<Intent>> histories(int maxSize) {
        return intents(Arbitraries.of(Kind.values())).list().ofMinSize(1).ofMaxSize(maxSize);
    }

    /** Acquisitions only, for the properties that need something to have been bought. */
    public static Arbitrary<List<Intent>> buysOnly(int maxSize) {
        return intents(Arbitraries.just(Kind.BUY)).list().ofMinSize(1).ofMaxSize(maxSize);
    }

    public static Arbitrary<Intent> intents(Arbitrary<Kind> kinds) {
        return Combinators.combine(
                        kinds,
                        Arbitraries.integers().between(0, GeneratedHistory.COMMODITIES.size() - 1),
                        Arbitraries.integers().between(0, GeneratedHistory.BROKERS.size() - 1),
                        quantities(),
                        prices(),
                        amounts(),
                        Arbitraries.integers().between(1, 100),
                        Arbitraries.of(
                                LotSelectionMethod.FIFO,
                                LotSelectionMethod.LIFO,
                                LotSelectionMethod.HIFO,
                                LotSelectionMethod.SPECIFIC_LOT))
                .as(Intent::new);
    }

    /** Whole share counts and fractional ones, because fractional shares are where rounding bites. */
    public static Arbitrary<BigDecimal> quantities() {
        Arbitrary<BigDecimal> whole = Arbitraries.integers().between(1, 500).map(BigDecimal::valueOf);
        Arbitrary<BigDecimal> fractional = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00000001"), new BigDecimal("500"))
                .ofScale(8);
        return Arbitraries.oneOf(whole, fractional);
    }

    /** Prices at full scale 6, including sub cent unit prices. */
    public static Arbitrary<BigDecimal> prices() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.000001"), new BigDecimal("5000"))
                .ofScale(6);
    }

    /**
     * Serves commissions, fees, dividends and transfer amounts. Exact in cents, so scale 2.
     * Zero is included because many brokers charge nothing, and the steps that need a
     * positive amount skip a zero rather than forcing one.
     */
    public static Arbitrary<BigDecimal> amounts() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("19.99"))
                .ofScale(2);
    }

    /** Strictly positive, for the fee property that needs the charge to actually happen. */
    public static Arbitrary<BigDecimal> positiveAmounts() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("999.99"))
                .ofScale(2);
    }
}
