package com.basis.ledger;

import static com.basis.support.Fixtures.AAPL;
import static com.basis.support.Fixtures.IBKR;
import static com.basis.support.Fixtures.JAN_15;
import static com.basis.support.Fixtures.USD;
import static com.basis.support.Fixtures.buy;
import static com.basis.support.Fixtures.qty;
import static com.basis.support.Fixtures.sell;
import static com.basis.support.Fixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basis.domain.Account;
import com.basis.domain.Lot;
import com.basis.domain.LotSelectionMethod;
import com.basis.domain.Money;
import com.basis.domain.Posting;
import com.basis.domain.Quantity;
import com.basis.domain.Transaction;
import com.basis.domain.event.ReverseSplit;
import com.basis.domain.event.Split;
import com.basis.domain.event.StockDividend;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Invariant 8: a corporate action preserves value.
 *
 * <p>Four things have to survive a share count restatement, and all four are asserted
 * here rather than assumed:
 *
 * <ul>
 *   <li>total basis, exactly, to the cent
 *   <li>the share count, scaled by the stated ratio
 *   <li>every lot's acquisition date, because a split does not restart a holding period
 *   <li>nothing realized, because nothing was sold
 * </ul>
 *
 * <p>Basis preservation is stated as an equality against what was booked to
 * {@link LedgerAccounts#ROUNDING}, not as "the number did not change". Six decimal places
 * of unit cost cannot always reproduce a basis to the cent over a large share count, so
 * the honest invariant is that nothing goes missing: every cent leaving the position is
 * accounted for in the ledger. The large position case below is the one that forces that
 * path, and it exists because a property test over realistic share counts would never
 * reach it.
 */
class CorporateActionValuePreservationTest {

    private static final Account HOLDING = LedgerAccounts.holding(IBKR, AAPL);

    @Test
    @DisplayName("a 4 for 1 split turns 10 shares at 150.00 into 40 at 37.50, basis untouched")
    void forwardSplitRestatesCountAndCost() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));

        Transaction txn = ledger.record(new Split(JAN_15.plusMonths(1), IBKR, "ca1",
                "{\"ratio\":\"4:1\"}", AAPL, 4, 1));

        assertThat(txn.postings()).hasSize(2);
        assertThat(txn.postings().get(0).quantity()).isEqualTo(qty("-10"));
        assertThat(txn.postings().get(1).quantity()).isEqualTo(qty("40"));
        assertThat(txn.postings().get(1).cost().unitCost().value()).isEqualByComparingTo("37.50");
        assertThat(BalanceChecker.isBalanced(txn.postings())).isTrue();

        assertThat(ledger.state().position(HOLDING, AAPL)).isEqualTo(qty("40"));
        assertThat(ledger.state().openBasis(HOLDING, AAPL, USD)).isEqualTo(usd("1500.00"));
        assertThat(residueOn(txn)).isEqualTo(Money.zero(USD));
    }

    @Test
    @DisplayName("the holding period survives a split, so a later gain is still measured from the purchase")
    void splitPreservesTheHoldingPeriod() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        ledger.record(new Split(JAN_15.plusMonths(1), IBKR, "ca1", "{}", AAPL, 4, 1));

        assertThat(ledger.state().openLots(HOLDING, AAPL)).singleElement()
                .satisfies(lot -> assertThat(lot.acquisitionDate())
                        .as("the split did not restart the clock")
                        .isEqualTo(JAN_15));

        Transaction sale = ledger.record(sell(JAN_15.plusMonths(2), "s1", AAPL, "40", "40.00", "0.00",
                LotSelectionMethod.FIFO));

        assertThat(sale.postings().get(0).cost().acquisitionDate()).isEqualTo(JAN_15);
        RealizedGain realized = ledger.state().realizedGains().get(0);
        assertThat(realized.basis()).isEqualTo(usd("1500.00"));
        assertThat(realized.proceeds()).isEqualTo(usd("1600.00"));
        assertThat(realized.gain()).isEqualTo(usd("100.00"));
    }

    @Test
    @DisplayName("a split realizes nothing, because nothing settled in cash")
    void splitRealizesNothing() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));

        ledger.record(new Split(JAN_15.plusMonths(1), IBKR, "ca1", "{}", AAPL, 4, 1));

        assertThat(ledger.state().realizedGains())
                .as("a split is a restatement, not a taxable event")
                .isEmpty();
    }

    @Test
    @DisplayName("a 1 for 8 reverse split leaves a fractional position and the same basis")
    void reverseSplitRestatesCountAndCost() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "100", "3.00", "0.00"));

        Transaction txn = ledger.record(new ReverseSplit(JAN_15.plusMonths(1), IBKR, "ca1",
                "{\"ratio\":\"1:8\"}", AAPL, 1, 8));

        assertThat(ledger.state().position(HOLDING, AAPL)).isEqualTo(qty("12.5"));
        assertThat(ledger.state().openBasis(HOLDING, AAPL, USD)).isEqualTo(usd("300.00"));
        assertThat(ledger.state().openLots(HOLDING, AAPL).get(0).unitCost().value())
                .isEqualByComparingTo("24.00");
        assertThat(residueOn(txn)).isEqualTo(Money.zero(USD));
        assertThat(ledger.state().realizedGains()).isEmpty();
    }

    @Test
    @DisplayName("a stock dividend dilutes existing basis rather than adding any")
    void stockDividendAddsSharesWithoutAddingBasis() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "80", "10.00", "0.00"));

        Transaction txn = ledger.record(new StockDividend(JAN_15.plusMonths(1), IBKR, "ca1",
                "{}", AAPL, qty("20")));

        assertThat(ledger.state().position(HOLDING, AAPL)).isEqualTo(qty("100"));
        assertThat(ledger.state().openBasis(HOLDING, AAPL, USD))
                .as("free shares add no basis, they dilute what was already there")
                .isEqualTo(usd("800.00"));
        assertThat(ledger.state().openLots(HOLDING, AAPL).get(0).unitCost().value())
                .isEqualByComparingTo("8.00");
        assertThat(residueOn(txn)).isEqualTo(Money.zero(USD));
    }

    @Test
    @DisplayName("every lot is restated, and each keeps its own date and its own cost")
    void multipleLotsAreRestatedIndependently() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        ledger.record(buy(JAN_15.plusDays(30), "b2", AAPL, "10", "200.00", "0.00"));
        Money basisBefore = ledger.state().openBasis(HOLDING, AAPL, USD);

        Transaction txn = ledger.record(new Split(JAN_15.plusMonths(2), IBKR, "ca1", "{}", AAPL, 2, 1));

        List<Lot> lots = ledger.state().openLots(HOLDING, AAPL);
        assertThat(lots).hasSize(2);
        assertThat(lots.get(0).acquisitionDate()).isEqualTo(JAN_15);
        assertThat(lots.get(0).unitCost().value()).isEqualByComparingTo("75.00");
        assertThat(lots.get(1).acquisitionDate()).isEqualTo(JAN_15.plusDays(30));
        assertThat(lots.get(1).unitCost().value()).isEqualByComparingTo("100.00");
        assertThat(ledger.state().openBasis(HOLDING, AAPL, USD)).isEqualTo(basisBefore);
        assertThat(residueOn(txn)).isEqualTo(Money.zero(USD));
    }

    @Test
    @DisplayName("a ratio that does not divide evenly still preserves basis to the cent")
    void awkwardRatioStillPreservesBasis() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "100.00", "0.00"));

        // 3 for 1 gives a unit cost of 33.333333..., which scale 6 cannot hold exactly.
        Transaction txn = ledger.record(new Split(JAN_15.plusMonths(1), IBKR, "ca1", "{}", AAPL, 3, 1));

        assertThat(ledger.state().position(HOLDING, AAPL)).isEqualTo(qty("30"));
        assertThat(ledger.state().openLots(HOLDING, AAPL).get(0).unitCost().value())
                .isEqualByComparingTo("33.333333");
        assertNothingWentMissing(ledger, txn, usd("1000.00"));
    }

    @Test
    @DisplayName("a large position forces the residue path, and the residue is booked not lost")
    void largePositionBooksItsResidueToEquity() {
        Ledger ledger = new Ledger();
        // Two million shares at a sub cent price: no scale 6 unit cost can reproduce this
        // basis to the cent after a 3 for 1 split. This is the case a property test over
        // realistic share counts would never generate.
        ledger.record(buy(JAN_15, "b1", AAPL, "2000000", "0.007777", "0.00"));
        Money basisBefore = ledger.state().openBasis(HOLDING, AAPL, USD);

        Transaction txn = ledger.record(new Split(JAN_15.plusMonths(1), IBKR, "ca1", "{}", AAPL, 3, 1));

        assertThat(ledger.state().position(HOLDING, AAPL)).isEqualTo(qty("6000000"));
        // Spelled out so this case cannot start passing vacuously. Basis is 15554.00 over
        // six million shares, which is 0.002592333... per share; scale 6 holds 0.002592,
        // and six million shares multiply that missing third of a millionth into 2.00.
        assertThat(residueOn(txn))
                .as("this is the case that forces the residue path, so it had better produce one")
                .isEqualTo(usd("2.00"));
        assertNothingWentMissing(ledger, txn, basisBefore);
        assertThat(BalanceChecker.isBalanced(txn.postings()))
                .as("the residue is what keeps the transaction balanced")
                .isTrue();
        assertThat(ledger.state().realizedGains())
                .as("the rounding residue is not proceeds, so nothing is realized")
                .isEmpty();
    }

    @Test
    @DisplayName("a forward split that does not increase the count is refused, not silently applied")
    void forwardSplitMustIncreaseTheCount() {
        assertThatThrownBy(() -> new Split(JAN_15, IBKR, "ca1", "{}", AAPL, 1, 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Use ReverseSplit");
    }

    @Test
    @DisplayName("a reverse split that does not reduce the count is refused")
    void reverseSplitMustReduceTheCount() {
        assertThatThrownBy(() -> new ReverseSplit(JAN_15, IBKR, "ca1", "{}", AAPL, 4, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Use Split");
    }

    @Test
    @DisplayName("a corporate action on a position that was never held is refused, not invented")
    void splitOnNothingIsRefused() {
        Ledger ledger = new Ledger();

        assertThatThrownBy(() -> ledger.record(new Split(JAN_15, IBKR, "ca1", "{}", AAPL, 4, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("break, not a transaction");
    }

    @Test
    @DisplayName("the same split replayed produces the same lot ids")
    void restatedLotIdsAreDeterministic() {
        assertThat(lotIdAfterASplit()).isEqualTo(lotIdAfterASplit());
    }

    private static String lotIdAfterASplit() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        ledger.record(new Split(JAN_15.plusMonths(1), IBKR, "ca1", "{}", AAPL, 4, 1));
        return ledger.state().openLots(HOLDING, AAPL).get(0).id().value();
    }

    /**
     * Invariant 8 proper: every cent that left the position is accounted for, and the
     * quantity is the only thing that changed.
     */
    private void assertNothingWentMissing(Ledger ledger, Transaction txn, Money basisBefore) {
        List<Lot> lots = ledger.state().openLots(HOLDING, AAPL);
        Money basisAfter = ledger.state().openBasis(HOLDING, AAPL, USD);
        Money residue = residueOn(txn);

        assertThat(basisAfter.plus(residue))
                .as("invariant 8, basis after plus the residue booked to %s equals basis before",
                        LedgerAccounts.ROUNDING)
                .isEqualTo(basisBefore);
        assertThat(Math.abs(residue.minorUnits()))
                .as("invariant 8, the residue is no larger than the arithmetic forces it to be")
                .isLessThanOrEqualTo(roundingBound(ledger.state().position(HOLDING, AAPL), lots.size()));
    }

    /**
     * The largest residue the representation can produce, derived rather than guessed.
     *
     * <p>A scale 6 unit cost is at most 5e-7 away from the exact basis per share, and that
     * error is multiplied by the share count. Rounding each lot's weight to whole minor
     * units adds at most half a cent more per lot. Anything beyond that is a bug, not
     * rounding, which is the point of bounding it at all.
     */
    private static long roundingBound(Quantity totalQuantity, int lotCount) {
        java.math.BigDecimal dollars = totalQuantity.value()
                .multiply(new java.math.BigDecimal("0.0000005"))
                .add(new java.math.BigDecimal("0.005").multiply(java.math.BigDecimal.valueOf(lotCount)));
        return dollars.movePointRight(2)
                .setScale(0, java.math.RoundingMode.CEILING)
                .longValueExact();
    }

    /** What the restatement booked to the rounding account, zero when it booked nothing. */
    private static Money residueOn(Transaction txn) {
        Money total = Money.zero(USD);
        for (Posting posting : txn.postings()) {
            if (posting.account().equals(LedgerAccounts.ROUNDING)) {
                total = total.plus(posting.weight());
            }
        }
        return total;
    }

    @Test
    @DisplayName("a spin off is still refused, and still names what it needs")
    void spinOffRemainsUnhandled() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));

        assertThatThrownBy(() -> ledger.record(new com.basis.domain.event.SpinOff(
                JAN_15.plusMonths(1), IBKR, "ca1", "{}", AAPL,
                com.basis.domain.Commodity.equity("NEWCO"), Quantity.of("0.5"),
                new java.math.BigDecimal("0.30"))))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("basis allocation");
    }
}
