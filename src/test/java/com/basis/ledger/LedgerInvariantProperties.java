package com.basis.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.basis.domain.LotSelectionMethod;
import com.basis.domain.Money;
import com.basis.support.Fixtures;
import com.basis.support.GeneratedHistory;
import com.basis.support.GeneratedHistory.Intent;
import com.basis.support.GeneratedHistory.Kind;
import com.basis.support.LedgerInvariants;
import java.math.BigDecimal;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Invariants 1 to 6 over randomly generated buy, sell and fee sequences, asserted after
 * every single step rather than only at the end.
 *
 * <p>Checking after every step is the point. A ledger bug that cancels itself out over a
 * long history is exactly the kind that survives an end state assertion, and it is also
 * exactly the kind that makes a single day's reconciliation wrong.
 *
 * <p>Invariant 7, replay determinism, needs a database and lives in
 * {@code com.basis.persistence.ReplayDeterminismProperties}. Invariant 8, corporate
 * action value preservation, is week 3 and has a deliberately failing placeholder.
 */
@Label("ledger invariants 1 to 6")
class LedgerInvariantProperties {

    @Property(tries = 300)
    @Label("invariants 1 to 6 hold after every step of a random history")
    void invariantsHoldAfterEveryStep(@ForAll("histories") List<Intent> intents) {
        GeneratedHistory history = GeneratedHistory.runChecking(intents, step -> {
            LedgerInvariants.assertAllHold(step.state(), step.recorded());
            LedgerInvariants.assertCashIsConserved(step.state(), step.cashAccount(), step.expectedCash());
        });

        assertThat(history.recorded())
                .as("the opening cash balance at least is always recorded")
                .isNotEmpty();
    }

    @Property(tries = 300)
    @Label("invariant 1, every generated transaction balances at cost")
    void transactionsBalance(@ForAll("histories") List<Intent> intents) {
        GeneratedHistory history = GeneratedHistory.run(intents);

        LedgerInvariants.assertTransactionsBalance(history.recorded());
    }

    @Property(tries = 300)
    @Label("invariant 2, lots are conserved: remaining equals acquired minus disposed, never negative")
    void lotsAreConserved(@ForAll("histories") List<Intent> intents) {
        GeneratedHistory history = GeneratedHistory.run(intents);

        LedgerInvariants.assertLotsAreConserved(history.state(), history.recorded());
    }

    @Property(tries = 300)
    @Label("invariant 3, every position equals the sum of its open lots")
    void positionsMatchOpenLots(@ForAll("histories") List<Intent> intents) {
        GeneratedHistory history = GeneratedHistory.run(intents);

        LedgerInvariants.assertPositionsMatchOpenLots(history.state());
    }

    @Property(tries = 300)
    @Label("invariant 4, total basis equals the sum over open lots of quantity times unit cost")
    void basisMatchesOpenLots(@ForAll("histories") List<Intent> intents) {
        GeneratedHistory history = GeneratedHistory.run(intents);

        LedgerInvariants.assertBasisMatchesOpenLots(history.state());
    }

    @Property(tries = 300)
    @Label("invariant 5, proceeds minus basis equals realized gain exactly, in minor units")
    void proceedsIdentityHolds(@ForAll("histories") List<Intent> intents) {
        GeneratedHistory history = GeneratedHistory.run(intents);

        LedgerInvariants.assertProceedsIdentity(history.state());

        // Cross checked against the events as well as within the ledger: the proceeds the
        // ledger recorded must be the gross consideration the sell event stated, so that
        // the identity cannot hold by both sides being wrong in the same direction.
        List<GeneratedHistory.ExpectedSale> sales = history.expectedSales();
        List<RealizedGain> gains = history.state().realizedGains();
        assertThat(gains).hasSameSizeAs(sales);
        for (int i = 0; i < sales.size(); i++) {
            assertThat(gains.get(i).proceeds())
                    .as("invariant 5, recorded proceeds equal the gross consideration of sale %d", i)
                    .isEqualTo(sales.get(i).proceeds());
            assertThat(gains.get(i).quantity())
                    .as("invariant 5, recorded quantity equals the disposed quantity of sale %d", i)
                    .isEqualTo(sales.get(i).quantity());
        }
    }

    @Property(tries = 300)
    @Label("invariant 6, no cash is created inside the system")
    void cashIsConserved(@ForAll("histories") List<Intent> intents) {
        GeneratedHistory history = GeneratedHistory.run(intents);

        LedgerInvariants.assertCashIsConserved(history.state(), history.cashAccount(), history.expectedCash());
    }

    @Property(tries = 200)
    @Label("a history of buys alone realizes nothing and holds everything")
    void buysAloneRealizeNothing(@ForAll("buysOnly") List<Intent> intents) {
        GeneratedHistory history = GeneratedHistory.run(intents);

        assertThat(history.state().realizedGains()).isEmpty();
        for (var lot : history.state().allLots()) {
            assertThat(lot.remainingQuantity())
                    .as("nothing was disposed, so every lot is whole")
                    .isEqualTo(lot.originalQuantity());
        }
        LedgerInvariants.assertAllHold(history.state(), history.recorded());
    }

    @Property(tries = 200)
    @Label("selling a holding down to nothing closes its lots and leaves no position")
    void sellingEverythingClosesEveryLot(
            @ForAll("buysOnly") List<Intent> buys,
            @ForAll @IntRange(min = 0, max = 2) int commodityIndex) {
        GeneratedHistory history = GeneratedHistory.run(buys);
        var commodity = GeneratedHistory.COMMODITIES.get(commodityIndex);
        var holding = LedgerAccounts.holding(Fixtures.IBKR, commodity);

        // 100 percent of the holding, repeated: one pass is enough, the second is a no op.
        List<Intent> liquidate = List.of(
                new Intent(Kind.SELL, commodityIndex, BigDecimal.ONE, new BigDecimal("123.456789"),
                        BigDecimal.ZERO, 100, LotSelectionMethod.FIFO));
        GeneratedHistory after = GeneratedHistory.run(concat(buys, liquidate));

        assertThat(after.state().position(holding, commodity)).isEqualTo(com.basis.domain.Quantity.ZERO);
        assertThat(after.state().openLots(holding, commodity)).isEmpty();
        LedgerInvariants.assertAllHold(after.state(), after.recorded());
        assertThat(history.state().position(holding, commodity).isNegative()).isFalse();
    }

    @Property(tries = 200)
    @Label("a fee never touches a position, only cash")
    void feesTouchOnlyCash(@ForAll("buysOnly") List<Intent> buys,
            @ForAll("feeAmounts") BigDecimal feeAmount) {
        GeneratedHistory before = GeneratedHistory.run(buys);
        Money fee = Money.of(feeAmount, Fixtures.USD);
        List<Intent> withFee = concat(buys, List.of(new Intent(Kind.FEE, 0, BigDecimal.ONE,
                BigDecimal.ONE, fee.toMajorUnits(), 1, LotSelectionMethod.FIFO)));

        GeneratedHistory after = GeneratedHistory.run(withFee);

        assertThat(after.state().positions().entrySet().stream()
                .filter(entry -> !entry.getKey().commodity().isCash())
                .toList())
                .as("a fee changes no security position")
                .isEqualTo(before.state().positions().entrySet().stream()
                        .filter(entry -> !entry.getKey().commodity().isCash())
                        .toList());
        assertThat(after.state().cash(after.cashAccount(), Fixtures.USD))
                .isEqualTo(before.state().cash(before.cashAccount(), Fixtures.USD).minus(fee));
        LedgerInvariants.assertAllHold(after.state(), after.recorded());
    }

    private static List<Intent> concat(List<Intent> first, List<Intent> second) {
        List<Intent> all = new java.util.ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    @Provide
    Arbitrary<List<Intent>> histories() {
        return intents(Arbitraries.of(Kind.values())).list().ofMinSize(1).ofMaxSize(20);
    }

    @Provide
    Arbitrary<List<Intent>> buysOnly() {
        return intents(Arbitraries.just(Kind.BUY)).list().ofMinSize(1).ofMaxSize(10);
    }

    private static Arbitrary<Intent> intents(Arbitrary<Kind> kinds) {
        return Combinators.combine(
                        kinds,
                        Arbitraries.integers().between(0, GeneratedHistory.COMMODITIES.size() - 1),
                        quantities(),
                        prices(),
                        commissions(),
                        Arbitraries.integers().between(1, 100),
                        Arbitraries.of(
                                LotSelectionMethod.FIFO,
                                LotSelectionMethod.LIFO,
                                LotSelectionMethod.HIFO,
                                LotSelectionMethod.SPECIFIC_LOT))
                .as(Intent::new);
    }

    /** Whole share counts and fractional ones, because fractional shares are where rounding bites. */
    private static Arbitrary<BigDecimal> quantities() {
        Arbitrary<BigDecimal> whole = Arbitraries.integers().between(1, 500).map(BigDecimal::valueOf);
        Arbitrary<BigDecimal> fractional = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.00000001"), new BigDecimal("500"))
                .ofScale(8);
        return Arbitraries.oneOf(whole, fractional);
    }

    /** Prices at full scale 6, including sub cent unit prices. */
    private static Arbitrary<BigDecimal> prices() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.000001"), new BigDecimal("5000"))
                .ofScale(6);
    }

    @Provide
    Arbitrary<BigDecimal> feeAmounts() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("999.99"))
                .ofScale(2);
    }

    /** Commissions must be exact in cents, so scale 2. Zero is included: many brokers charge nothing. */
    private static Arbitrary<BigDecimal> commissions() {
        return Arbitraries.bigDecimals()
                .between(BigDecimal.ZERO, new BigDecimal("19.99"))
                .ofScale(2);
    }
}
