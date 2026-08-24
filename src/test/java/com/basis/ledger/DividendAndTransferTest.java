package com.basis.ledger;

import static com.basis.support.Fixtures.AAPL;
import static com.basis.support.Fixtures.BANK;
import static com.basis.support.Fixtures.FEB_01;
import static com.basis.support.Fixtures.IBKR;
import static com.basis.support.Fixtures.IBKR_CASH;
import static com.basis.support.Fixtures.JAN_15;
import static com.basis.support.Fixtures.SCHWAB;
import static com.basis.support.Fixtures.SCHWAB_CASH;
import static com.basis.support.Fixtures.USD;
import static com.basis.support.Fixtures.buy;
import static com.basis.support.Fixtures.dividend;
import static com.basis.support.Fixtures.qty;
import static com.basis.support.Fixtures.transferCash;
import static com.basis.support.Fixtures.transferSecurity;
import static com.basis.support.Fixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Lot;
import com.basis.domain.LotSelectionMethod;
import com.basis.domain.Money;
import com.basis.domain.Posting;
import com.basis.domain.Transaction;
import com.basis.domain.event.Transfer;
import com.basis.ledger.lot.InsufficientLotsException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Week 2: cash distributions and movement between accounts. */
class DividendAndTransferTest {

    private static final Account IBKR_AAPL = LedgerAccounts.holding(IBKR, AAPL);
    private static final Account SCHWAB_AAPL = LedgerAccounts.holding(SCHWAB, AAPL);

    @Nested
    class Dividends {

        @Test
        @DisplayName("a dividend credits income, expenses the withholding, and pays the net into cash")
        void dividendBooksGrossIncomeAndWithholdingSeparately() {
            Ledger ledger = new Ledger();

            Transaction txn = ledger.record(dividend(FEB_01, "d1", AAPL, "100.00", "15.00"));

            assertThat(txn.postings()).hasSize(3);
            assertThat(weightOn(txn, LedgerAccounts.dividendIncome(AAPL))).isEqualTo(usd("-100.00"));
            assertThat(weightOn(txn, LedgerAccounts.WITHHOLDING_TAX)).isEqualTo(usd("15.00"));
            assertThat(weightOn(txn, IBKR_CASH)).isEqualTo(usd("85.00"));
            assertThat(BalanceChecker.isBalanced(txn.postings())).isTrue();
            assertThat(ledger.state().cash(IBKR_CASH, USD)).isEqualTo(usd("85.00"));
        }

        @Test
        @DisplayName("income is booked per commodity, so a break can name the payer")
        void incomeIsBookedPerCommodity() {
            assertThat(LedgerAccounts.dividendIncome(AAPL).name()).isEqualTo("Income:Dividends:AAPL");
        }

        @Test
        @DisplayName("no withholding means no withholding leg")
        void zeroWithholdingEmitsNoLeg() {
            Ledger ledger = new Ledger();

            Transaction txn = ledger.record(dividend(FEB_01, "d1", AAPL, "100.00", "0.00"));

            assertThat(txn.postings()).hasSize(2);
            assertThat(txn.postings()).noneMatch(p -> p.account().equals(LedgerAccounts.WITHHOLDING_TAX));
            assertThat(ledger.state().cash(IBKR_CASH, USD)).isEqualTo(usd("100.00"));
        }

        @Test
        @DisplayName("a dividend realizes no gain, because nothing was disposed of")
        void dividendRealizesNothing() {
            Ledger ledger = new Ledger();
            ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));

            ledger.record(dividend(FEB_01, "d1", AAPL, "24.00", "0.00"));

            assertThat(ledger.state().realizedGains()).isEmpty();
            assertThat(ledger.state().position(IBKR_AAPL, AAPL)).isEqualTo(qty("10"));
        }

        @Test
        @DisplayName("withholding cannot exceed the dividend it was taken from")
        void withholdingCannotExceedGross() {
            assertThatThrownBy(() -> dividend(FEB_01, "d1", AAPL, "10.00", "11.00"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds the gross dividend");
        }
    }

    @Nested
    class CashTransfers {

        @Test
        @DisplayName("cash moves between accounts and the total is unchanged")
        void cashMovesWithoutBeingCreated() {
            Ledger ledger = new Ledger();
            ledger.record(com.basis.support.Fixtures.openingCash(JAN_15, "o1", "5000.00"));

            Transaction txn = ledger.record(transferCash(FEB_01, "t1", IBKR, SCHWAB, "1200.00"));

            assertThat(txn.postings()).hasSize(2);
            assertThat(weightOn(txn, SCHWAB_CASH)).isEqualTo(usd("1200.00"));
            assertThat(weightOn(txn, IBKR_CASH)).isEqualTo(usd("-1200.00"));
            assertThat(ledger.state().cash(IBKR_CASH, USD)).isEqualTo(usd("3800.00"));
            assertThat(ledger.state().cash(SCHWAB_CASH, USD)).isEqualTo(usd("1200.00"));
            assertThat(BalanceChecker.isBalanced(txn.postings())).isTrue();
        }

        @Test
        @DisplayName("a deposit is a transfer from an account outside the brokerage")
        void aDepositIsATransferFromOutside() {
            Ledger ledger = new Ledger();

            ledger.record(transferCash(JAN_15, "t1", BANK, IBKR, "2500.00"));

            assertThat(ledger.state().cash(IBKR_CASH, USD)).isEqualTo(usd("2500.00"));
            assertThat(ledger.state().cash(LedgerAccounts.cash(BANK), USD)).isEqualTo(usd("-2500.00"));
        }

        @Test
        @DisplayName("transferring to the same account is refused")
        void refusesSelfTransfer() {
            assertThatThrownBy(() -> transferCash(JAN_15, "t1", IBKR, IBKR, "100.00"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("same");
        }
    }

    @Nested
    class SecurityTransfers {

        @Test
        @DisplayName("a transferred lot arrives with its original acquisition date and unit cost")
        void transferPreservesTheHoldingPeriodAndBasis() {
            Ledger ledger = new Ledger();
            ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));

            Transaction txn = ledger.record(transferSecurity(FEB_01, "t1", IBKR, SCHWAB, AAPL, "10",
                    LotSelectionMethod.FIFO));

            assertThat(txn.postings()).hasSize(2);
            assertThat(BalanceChecker.isBalanced(txn.postings())).isTrue();

            assertThat(ledger.state().position(IBKR_AAPL, AAPL)).isEqualTo(com.basis.domain.Quantity.ZERO);
            List<Lot> received = ledger.state().openLots(SCHWAB_AAPL, AAPL);
            assertThat(received).singleElement().satisfies(lot -> {
                assertThat(lot.remainingQuantity()).isEqualTo(qty("10"));
                assertThat(lot.acquisitionDate())
                        .as("the holding period did not restart")
                        .isEqualTo(JAN_15);
                assertThat(lot.unitCost().value()).isEqualByComparingTo("150.00");
            });
        }

        @Test
        @DisplayName("a transfer realizes nothing, because no cash settled it")
        void transferRealizesNothing() {
            Ledger ledger = new Ledger();
            ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));

            ledger.record(transferSecurity(FEB_01, "t1", IBKR, SCHWAB, AAPL, "10", LotSelectionMethod.FIFO));

            assertThat(ledger.state().realizedGains())
                    .as("moving shares between accounts is paperwork, not a taxable event")
                    .isEmpty();
        }

        @Test
        @DisplayName("total quantity and total basis survive the move")
        void quantityAndBasisAreConserved() {
            Ledger ledger = new Ledger();
            ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
            Money basisBefore = ledger.state().openBasis(IBKR_AAPL, AAPL, USD);

            ledger.record(transferSecurity(FEB_01, "t1", IBKR, SCHWAB, AAPL, "4", LotSelectionMethod.FIFO));

            assertThat(ledger.state().position(IBKR_AAPL, AAPL)).isEqualTo(qty("6"));
            assertThat(ledger.state().position(SCHWAB_AAPL, AAPL)).isEqualTo(qty("4"));
            assertThat(ledger.state().openBasis(IBKR_AAPL, AAPL, USD)
                    .plus(ledger.state().openBasis(SCHWAB_AAPL, AAPL, USD)))
                    .isEqualTo(basisBefore);
        }

        @Test
        @DisplayName("selling after a transfer uses the original cost, not the transfer date")
        void gainAfterATransferUsesTheOriginalBasis() {
            Ledger ledger = new Ledger();
            ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
            ledger.record(transferSecurity(FEB_01, "t1", IBKR, SCHWAB, AAPL, "10", LotSelectionMethod.FIFO));

            Transaction sale = ledger.record(new com.basis.domain.event.Sell(
                    FEB_01.plusDays(1), SCHWAB, "s1", "{\"ref\":\"s1\"}", AAPL,
                    qty("5"), com.basis.support.Fixtures.price("160.00"), usd("0.00"),
                    LotSelectionMethod.FIFO, List.of()));

            Posting disposal = sale.postings().get(0);
            assertThat(disposal.cost().unitCost().value()).isEqualByComparingTo("150.00");
            assertThat(disposal.cost().acquisitionDate())
                    .as("the gain is long or short term against the original purchase")
                    .isEqualTo(JAN_15);

            RealizedGain realized = ledger.state().realizedGains().get(0);
            assertThat(realized.basis()).isEqualTo(usd("750.00"));
            assertThat(realized.gain()).isEqualTo(usd("50.00"));
            assertThat(realized.account()).isEqualTo(SCHWAB_AAPL);
        }

        @Test
        @DisplayName("the receiving lot gets its own id, traceable but distinct")
        void receivingLotHasItsOwnId() {
            Ledger ledger = new Ledger();
            ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
            String sourceLot = ledger.state().openLots(IBKR_AAPL, AAPL).get(0).id().value();

            ledger.record(transferSecurity(FEB_01, "t1", IBKR, SCHWAB, AAPL, "10", LotSelectionMethod.FIFO));
            String receivedLot = ledger.state().openLots(SCHWAB_AAPL, AAPL).get(0).id().value();

            assertThat(receivedLot).isNotEqualTo(sourceLot);
            assertThat(receivedLot)
                    .as("hashed, so an id stays a fixed length however many times a position moves")
                    .hasSameSizeAs(sourceLot);
        }

        @Test
        @DisplayName("the same transfer replayed produces the same lot ids")
        void transferLotIdsAreDeterministic() {
            assertThat(receivedLotIdAfterATransfer()).isEqualTo(receivedLotIdAfterATransfer());
        }

        private String receivedLotIdAfterATransfer() {
            Ledger ledger = new Ledger();
            ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
            ledger.record(transferSecurity(FEB_01, "t1", IBKR, SCHWAB, AAPL, "10", LotSelectionMethod.FIFO));
            return ledger.state().openLots(SCHWAB_AAPL, AAPL).get(0).id().value();
        }

        @Test
        @DisplayName("HIFO moves the most expensive lots, leaving the cheap basis behind")
        void transferHonoursTheStatedSelectionMethod() {
            Ledger ledger = new Ledger();
            ledger.record(buy(JAN_15, "b1", AAPL, "10", "100.00", "0.00"));
            ledger.record(buy(JAN_15.plusDays(1), "b2", AAPL, "10", "200.00", "0.00"));

            ledger.record(transferSecurity(FEB_01, "t1", IBKR, SCHWAB, AAPL, "10", LotSelectionMethod.HIFO));

            assertThat(ledger.state().openBasis(SCHWAB_AAPL, AAPL, USD)).isEqualTo(usd("2000.00"));
            assertThat(ledger.state().openBasis(IBKR_AAPL, AAPL, USD)).isEqualTo(usd("1000.00"));
        }

        @Test
        @DisplayName("transferring more than is held is refused")
        void refusesToTransferMoreThanIsHeld() {
            Ledger ledger = new Ledger();
            ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));

            assertThatThrownBy(() -> ledger.record(
                    transferSecurity(FEB_01, "t1", IBKR, SCHWAB, AAPL, "11", LotSelectionMethod.FIFO)))
                    .isInstanceOf(InsufficientLotsException.class);
        }

        @Test
        @DisplayName("a transfer cannot name specific lots, because there is nowhere to name them")
        void refusesSpecificLotSelection() {
            assertThatThrownBy(() -> new Transfer(FEB_01, IBKR, SCHWAB, "t1", "{}", AAPL,
                    qty("5"), LotSelectionMethod.SPECIFIC_LOT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot name specific lots");
        }
    }

    @Test
    @DisplayName("a spin off is still refused, and names the input it is missing")
    void spinOffRemainsUnhandled() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));

        assertThatThrownBy(() -> ledger.record(new com.basis.domain.event.SpinOff(
                FEB_01, IBKR, "ca1", "{}", AAPL, Commodity.equity("NEWCO"),
                qty("0.5"), new java.math.BigDecimal("0.30"))))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("basis allocation");
    }

    private static Money weightOn(Transaction txn, Account account) {
        List<Posting> matching = txn.postings().stream()
                .filter(posting -> posting.account().equals(account))
                .toList();
        assertThat(matching).as("postings on %s", account).hasSize(1);
        return matching.get(0).weight();
    }
}
