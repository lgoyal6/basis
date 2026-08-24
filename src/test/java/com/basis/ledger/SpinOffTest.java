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
import com.basis.domain.Commodity;
import com.basis.domain.LotSelectionMethod;
import com.basis.domain.Money;
import com.basis.domain.Posting;
import com.basis.domain.Quantity;
import com.basis.domain.Transaction;
import com.basis.domain.event.SpinOff;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A spin off allocates one position's basis across two commodities.
 *
 * <p>The allocation fraction is published by the issuer on Form 8937 and cannot be derived
 * from any price feed, which is why it arrives on the event rather than being computed.
 * Everything else here is the same conservation argument as a split: basis is preserved,
 * the holding period carries over, and nothing is realized because nothing settled.
 */
class SpinOffTest {

    private static final Commodity NEWCO = Commodity.equity("NEWCO");
    private static final Account AAPL_HOLDING = LedgerAccounts.holding(IBKR, AAPL);
    private static final Account NEWCO_HOLDING = LedgerAccounts.holding(IBKR, NEWCO);
    private static final LocalDate SPIN_DATE = JAN_15.plusMonths(1);

    @Test
    @DisplayName("30 percent of basis moves to the spun off shares, and the parent keeps the rest")
    void allocatesBasisByTheIssuerFraction() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "100", "10.00", "0.00"));

        Transaction txn = ledger.record(spinOff("0.5", "0.30"));

        assertThat(txn.postings()).hasSize(3);
        assertThat(BalanceChecker.isBalanced(txn.postings())).isTrue();

        assertThat(ledger.state().position(AAPL_HOLDING, AAPL))
                .as("a spin off does not change the parent share count")
                .isEqualTo(qty("100"));
        assertThat(ledger.state().position(NEWCO_HOLDING, NEWCO)).isEqualTo(qty("50"));

        assertThat(ledger.state().openBasis(AAPL_HOLDING, AAPL, USD)).isEqualTo(usd("700.00"));
        assertThat(ledger.state().openBasis(NEWCO_HOLDING, NEWCO, USD)).isEqualTo(usd("300.00"));
        assertThat(ledger.state().openLots(AAPL_HOLDING, AAPL).get(0).unitCost().value())
                .isEqualByComparingTo("7.00");
        assertThat(ledger.state().openLots(NEWCO_HOLDING, NEWCO).get(0).unitCost().value())
                .isEqualByComparingTo("6.00");
        assertThat(residueOn(txn)).isEqualTo(Money.zero(USD));
    }

    @Test
    @DisplayName("total basis across both holdings is exactly what it was before")
    void totalBasisIsPreserved() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "100", "10.00", "0.00"));
        Money before = ledger.state().openBasis(AAPL_HOLDING, AAPL, USD);

        Transaction txn = ledger.record(spinOff("0.5", "0.37"));

        Money after = ledger.state().openBasis(AAPL_HOLDING, AAPL, USD)
                .plus(ledger.state().openBasis(NEWCO_HOLDING, NEWCO, USD));
        assertThat(after.plus(residueOn(txn)))
                .as("nothing was created and nothing went missing")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("the spun off shares inherit the parent's holding period")
    void holdingPeriodCarriesOver() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "100", "10.00", "0.00"));

        ledger.record(spinOff("0.5", "0.30"));

        assertThat(ledger.state().openLots(NEWCO_HOLDING, NEWCO)).singleElement()
                .satisfies(lot -> assertThat(lot.acquisitionDate())
                        .as("US rules give the new shares the parent's holding period")
                        .isEqualTo(JAN_15));

        Transaction sale = ledger.record(new com.basis.domain.event.Sell(
                SPIN_DATE.plusDays(1), IBKR, "s1", "{}", NEWCO, qty("50"),
                com.basis.support.Fixtures.price("8.00"), usd("0.00"),
                LotSelectionMethod.FIFO, java.util.List.of()));

        assertThat(sale.postings().get(0).cost().acquisitionDate()).isEqualTo(JAN_15);
        assertThat(ledger.state().realizedGains().get(0).gain()).isEqualTo(usd("100.00"));
    }

    @Test
    @DisplayName("a spin off realizes nothing, because nothing settled in cash")
    void realizesNothing() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "100", "10.00", "0.00"));

        ledger.record(spinOff("0.5", "0.30"));

        assertThat(ledger.state().realizedGains()).isEmpty();
    }

    @Test
    @DisplayName("every parent lot is allocated separately, each keeping its own date")
    void eachLotIsAllocatedSeparately() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "100", "10.00", "0.00"));
        ledger.record(buy(JAN_15.plusDays(10), "b2", AAPL, "100", "20.00", "0.00"));

        Transaction txn = ledger.record(spinOff("0.5", "0.25"));

        assertThat(ledger.state().openLots(NEWCO_HOLDING, NEWCO)).hasSize(2);
        assertThat(ledger.state().openLots(NEWCO_HOLDING, NEWCO).get(0).acquisitionDate())
                .isEqualTo(JAN_15);
        assertThat(ledger.state().openLots(NEWCO_HOLDING, NEWCO).get(1).acquisitionDate())
                .isEqualTo(JAN_15.plusDays(10));
        assertThat(ledger.state().openBasis(NEWCO_HOLDING, NEWCO, USD)).isEqualTo(usd("750.00"));
        assertThat(ledger.state().openBasis(AAPL_HOLDING, AAPL, USD)).isEqualTo(usd("2250.00"));
        assertThat(residueOn(txn)).isEqualTo(Money.zero(USD));
    }

    @Test
    @DisplayName("a zero basis allocation still delivers the shares, at zero cost")
    void zeroFractionMovesNoBasis() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "100", "10.00", "0.00"));

        ledger.record(spinOff("0.5", "0"));

        assertThat(ledger.state().openBasis(AAPL_HOLDING, AAPL, USD)).isEqualTo(usd("1000.00"));
        assertThat(ledger.state().position(NEWCO_HOLDING, NEWCO)).isEqualTo(qty("50"));
        assertThat(ledger.state().openBasis(NEWCO_HOLDING, NEWCO, USD)).isEqualTo(Money.zero(USD));
    }

    @Test
    @DisplayName("allocating all of the basis leaves the parent holding shares at zero cost")
    void fullFractionMovesEverything() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "100", "10.00", "0.00"));

        ledger.record(spinOff("0.5", "1"));

        assertThat(ledger.state().openBasis(AAPL_HOLDING, AAPL, USD)).isEqualTo(Money.zero(USD));
        assertThat(ledger.state().openBasis(NEWCO_HOLDING, NEWCO, USD)).isEqualTo(usd("1000.00"));
        assertThat(ledger.state().position(AAPL_HOLDING, AAPL)).isEqualTo(qty("100"));
    }

    @Test
    @DisplayName("the parent lot and the spun off lot get different ids, not the same one twice")
    void parentAndSpunLotsAreDistinct() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "100", "10.00", "0.00"));

        ledger.record(spinOff("0.5", "0.30"));

        String parentLot = ledger.state().openLots(AAPL_HOLDING, AAPL).get(0).id().value();
        String spunLot = ledger.state().openLots(NEWCO_HOLDING, NEWCO).get(0).id().value();
        assertThat(parentLot)
                .as("both are derived from the same source lot, so the role has to be in the hash")
                .isNotEqualTo(spunLot);
    }

    @Test
    @DisplayName("the same spin off replayed produces the same lot ids")
    void lotIdsAreDeterministic() {
        assertThat(spunLotIdAfterASpinOff()).isEqualTo(spunLotIdAfterASpinOff());
    }

    private static String spunLotIdAfterASpinOff() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "100", "10.00", "0.00"));
        ledger.record(new SpinOff(SPIN_DATE, IBKR, "ca1", "{}", AAPL, NEWCO,
                qty("0.5"), new BigDecimal("0.30")));
        return ledger.state().openLots(NEWCO_HOLDING, NEWCO).get(0).id().value();
    }

    @Test
    @DisplayName("a fractional entitlement that rounds away keeps its basis with the parent")
    void basisStaysWithTheParentWhenNoSharesArrive() {
        Ledger ledger = new Ledger();
        // 0.00000001 parent shares at a ratio of 0.00000001 rounds to no shares at all.
        ledger.record(buy(JAN_15, "b1", AAPL, "0.00000001", "1000.00", "0.00"));
        Money before = ledger.state().openBasis(AAPL_HOLDING, AAPL, USD);

        ledger.record(spinOff("0.00000001", "0.50"));

        assertThat(ledger.state().position(NEWCO_HOLDING, NEWCO)).isEqualTo(Quantity.ZERO);
        assertThat(ledger.state().openBasis(AAPL_HOLDING, AAPL, USD))
                .as("basis is not moved to a position that rounded away")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("a spin off from a position that was never held is refused")
    void spinOffOnNothingIsRefused() {
        Ledger ledger = new Ledger();

        assertThatThrownBy(() -> ledger.record(spinOff("0.5", "0.30")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("break, not a transaction");
    }

    @Test
    @DisplayName("a company cannot spin off itself")
    void refusesSelfSpinOff() {
        assertThatThrownBy(() -> new SpinOff(SPIN_DATE, IBKR, "ca1", "{}", AAPL, AAPL,
                qty("0.5"), new BigDecimal("0.30")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot spin off itself");
    }

    @Test
    @DisplayName("an allocation fraction above one is refused")
    void refusesFractionAboveOne() {
        assertThatThrownBy(() -> new SpinOff(SPIN_DATE, IBKR, "ca1", "{}", AAPL, NEWCO,
                qty("0.5"), new BigDecimal("1.01")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 1");
    }

    @Test
    @DisplayName("selling the parent after a spin off uses the reduced basis")
    void parentGainUsesTheReducedBasis() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "100", "10.00", "0.00"));
        ledger.record(spinOff("0.5", "0.30"));

        ledger.record(sell(SPIN_DATE.plusDays(1), "s1", AAPL, "100", "12.00", "0.00",
                LotSelectionMethod.FIFO));

        RealizedGain realized = ledger.state().realizedGains().get(0);
        assertThat(realized.basis())
                .as("the parent kept 700.00 of the original 1000.00")
                .isEqualTo(usd("700.00"));
        assertThat(realized.gain()).isEqualTo(usd("500.00"));
    }

    private static SpinOff spinOff(String perShare, String fraction) {
        return new SpinOff(SPIN_DATE, IBKR, "ca1", "{\"form8937\":\"" + fraction + "\"}",
                AAPL, NEWCO, qty(perShare), new BigDecimal(fraction));
    }

    private static Money residueOn(Transaction txn) {
        Money total = Money.zero(USD);
        for (Posting posting : txn.postings()) {
            if (posting.account().equals(LedgerAccounts.ROUNDING)) {
                total = total.plus(posting.weight());
            }
        }
        return total;
    }
}
