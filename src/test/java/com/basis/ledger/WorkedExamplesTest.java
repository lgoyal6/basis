package com.basis.ledger;

import static com.basis.support.Fixtures.AAPL;
import static com.basis.support.Fixtures.FEB_01;
import static com.basis.support.Fixtures.IBKR;
import static com.basis.support.Fixtures.IBKR_CASH;
import static com.basis.support.Fixtures.JAN_15;
import static com.basis.support.Fixtures.USD;
import static com.basis.support.Fixtures.buy;
import static com.basis.support.Fixtures.qty;
import static com.basis.support.Fixtures.sell;
import static com.basis.support.Fixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basis.domain.Account;
import com.basis.domain.LotSelectionMethod;
import com.basis.domain.Money;
import com.basis.domain.Posting;
import com.basis.domain.Transaction;
import com.basis.ledger.lot.AverageCostNotPermittedException;
import com.basis.ledger.lot.InsufficientLotsException;
import com.basis.support.Fixtures;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two worked examples from the week 1 mandate, posting for posting.
 *
 * <p>These are the acceptance criteria the ledger was specified against, so they are
 * asserted literally rather than paraphrased: same accounts, same signs, same lot
 * annotation, same weights, same sum of zero.
 */
class WorkedExamplesTest {

    @Test
    @DisplayName("Buy 10 AAPL at 150.00 with 1.00 commission produces the three specified postings")
    void buyProducesTheSpecifiedPostings() {
        Ledger ledger = new Ledger();

        Transaction txn = ledger.record(buy(JAN_15, "e1", AAPL, "10", "150.00", "1.00"));

        assertThat(txn.postings()).hasSize(3);

        Posting shares = txn.postings().get(0);
        assertThat(shares.account()).isEqualTo(Account.of("Assets:Broker:IBKR:AAPL"));
        assertThat(shares.quantity()).isEqualTo(qty("10"));
        assertThat(shares.commodity()).isEqualTo(AAPL);
        assertThat(shares.cost().unitCost().value()).isEqualByComparingTo("150.00");
        assertThat(shares.cost().acquisitionDate()).isEqualTo(JAN_15);
        assertThat(shares.weight()).isEqualTo(usd("1500.00"));

        Posting commission = txn.postings().get(1);
        assertThat(commission.account()).isEqualTo(LedgerAccounts.COMMISSIONS);
        assertThat(commission.weight()).isEqualTo(usd("1.00"));

        Posting cash = txn.postings().get(2);
        assertThat(cash.account()).isEqualTo(IBKR_CASH);
        assertThat(cash.weight()).isEqualTo(usd("-1501.00"));

        assertThat(BalanceChecker.residual(txn.postings(), USD)).isEqualTo(Money.zero(USD));
    }

    @Test
    @DisplayName("Sell 5 at 160.00 realizes 50.00 as the balancing plug, not as a computed field")
    void sellDerivesRealizedGainFromTheBalanceRequirement() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "e1", AAPL, "10", "150.00", "0.00"));

        Transaction txn = ledger.record(sell(FEB_01, "e2", AAPL, "5", "160.00", "0.00",
                LotSelectionMethod.FIFO));

        assertThat(txn.postings()).hasSize(3);

        Posting shares = txn.postings().get(0);
        assertThat(shares.quantity()).isEqualTo(qty("-5"));
        assertThat(shares.cost().unitCost().value()).isEqualByComparingTo("150.00");
        assertThat(shares.cost().acquisitionDate()).isEqualTo(JAN_15);
        assertThat(shares.weight()).isEqualTo(usd("-750.00"));

        Posting cash = txn.postings().get(1);
        assertThat(cash.account()).isEqualTo(IBKR_CASH);
        assertThat(cash.weight()).isEqualTo(usd("800.00"));

        Posting gain = txn.postings().get(2);
        assertThat(gain.account()).isEqualTo(LedgerAccounts.REALIZED_GAINS);
        assertThat(gain.weight()).isEqualTo(usd("-50.00"));

        assertThat(BalanceChecker.residual(txn.postings(), USD)).isEqualTo(Money.zero(USD));

        RealizedGain realized = ledger.state().realizedGains().get(0);
        assertThat(realized.proceeds()).isEqualTo(usd("800.00"));
        assertThat(realized.basis()).isEqualTo(usd("750.00"));
        assertThat(realized.gain()).isEqualTo(usd("50.00"));
        assertThat(realized.satisfiesProceedsIdentity()).isTrue();
    }

    @Test
    @DisplayName("the disposal leaves the remainder of the lot open")
    void partialDisposalLeavesTheLotOpen() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "e1", AAPL, "10", "150.00", "0.00"));
        ledger.record(sell(FEB_01, "e2", AAPL, "5", "160.00", "0.00", LotSelectionMethod.FIFO));

        Account holding = LedgerAccounts.holding(IBKR, AAPL);
        assertThat(ledger.state().position(holding, AAPL)).isEqualTo(qty("5"));
        assertThat(ledger.state().openLots(holding, AAPL)).singleElement()
                .satisfies(lot -> {
                    assertThat(lot.originalQuantity()).isEqualTo(qty("10"));
                    assertThat(lot.remainingQuantity()).isEqualTo(qty("5"));
                    assertThat(lot.disposedQuantity()).isEqualTo(qty("5"));
                });
    }

    @Test
    @DisplayName("a wash sale emits no gain posting rather than a zero one")
    void zeroGainEmitsNoPlugPosting() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "e1", AAPL, "10", "150.00", "0.00"));

        Transaction txn = ledger.record(sell(FEB_01, "e2", AAPL, "10", "150.00", "0.00",
                LotSelectionMethod.FIFO));

        assertThat(txn.postings()).hasSize(2);
        assertThat(txn.postings()).noneMatch(p -> p.account().equals(LedgerAccounts.REALIZED_GAINS));
        assertThat(BalanceChecker.isBalanced(txn.postings())).isTrue();
    }

    @Test
    @DisplayName("a commission on a sale is expensed and does not reduce the reported gain")
    void commissionOnASaleIsExpensedNotNetted() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "e1", AAPL, "10", "150.00", "0.00"));

        Transaction txn = ledger.record(sell(FEB_01, "e2", AAPL, "5", "160.00", "1.00",
                LotSelectionMethod.FIFO));

        assertThat(txn.postings()).hasSize(4);
        assertThat(cashWeight(txn)).isEqualTo(usd("799.00"));
        assertThat(ledger.state().realizedGains().get(0).gain()).isEqualTo(usd("50.00"));
        assertThat(BalanceChecker.isBalanced(txn.postings())).isTrue();
    }

    @Test
    @DisplayName("a cash opening balance is plugged to Equity:Opening-Balances")
    void openingCashBalanceIsPluggedToEquity() {
        Ledger ledger = new Ledger();

        Transaction txn = ledger.record(Fixtures.openingCash(JAN_15, "e0", "10000.00"));

        assertThat(txn.postings()).hasSize(2);
        assertThat(cashWeight(txn)).isEqualTo(usd("10000.00"));
        assertThat(weightOn(txn, LedgerAccounts.OPENING_BALANCES)).isEqualTo(usd("-10000.00"));
        assertThat(ledger.state().cash(IBKR_CASH, USD)).isEqualTo(usd("10000.00"));
    }

    @Test
    @DisplayName("a security opening balance opens a lot at its stated cost")
    void openingSecurityBalanceOpensALot() {
        Ledger ledger = new Ledger();

        ledger.record(Fixtures.openingSecurity(JAN_15, "e0", AAPL, "40", "90.00"));

        Account holding = LedgerAccounts.holding(IBKR, AAPL);
        assertThat(ledger.state().openLots(holding, AAPL)).singleElement()
                .satisfies(lot -> {
                    assertThat(lot.remainingQuantity()).isEqualTo(qty("40"));
                    assertThat(lot.unitCost().value()).isEqualByComparingTo("90.00");
                    assertThat(lot.acquisitionDate()).isEqualTo(JAN_15);
                });
    }

    @Test
    @DisplayName("selling more than is held is refused rather than going short")
    void sellingMoreThanHeldIsRefused() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "e1", AAPL, "10", "150.00", "0.00"));

        assertThatThrownBy(() -> ledger.record(
                sell(FEB_01, "e2", AAPL, "11", "160.00", "0.00", LotSelectionMethod.FIFO)))
                .isInstanceOf(InsufficientLotsException.class)
                .hasMessageContaining("AAPL")
                .hasMessageContaining("11");
    }

    @Test
    @DisplayName("average cost on an equity is refused permanently, not deferred")
    void averageCostOnAnEquityIsRefused() {
        Ledger ledger = new Ledger();
        ledger.record(buy(JAN_15, "e1", AAPL, "10", "150.00", "0.00"));

        assertThatThrownBy(() -> ledger.record(
                sell(FEB_01, "e2", AAPL, "5", "160.00", "0.00", LotSelectionMethod.AVERAGE_COST)))
                .isInstanceOf(AverageCostNotPermittedException.class)
                .hasMessageContaining("mutual fund");
    }


    private static Money cashWeight(Transaction txn) {
        return weightOn(txn, IBKR_CASH);
    }

    private static Money weightOn(Transaction txn, Account account) {
        List<Posting> matching = txn.postings().stream()
                .filter(posting -> posting.account().equals(account))
                .toList();
        assertThat(matching).as("postings on %s", account).hasSize(1);
        return matching.get(0).weight();
    }
}
