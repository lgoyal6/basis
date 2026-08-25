package com.basis.ledger;

import static com.basis.support.Fixtures.AAPL;
import static com.basis.support.Fixtures.IBKR;
import static com.basis.support.Fixtures.JAN_15;
import static com.basis.support.Fixtures.SCHWAB;
import static com.basis.support.Fixtures.USD;
import static com.basis.support.Fixtures.qty;
import static com.basis.support.Fixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;

import com.basis.domain.Commodity;
import com.basis.domain.LotSelectionMethod;
import com.basis.domain.Money;
import com.basis.domain.Price;
import com.basis.domain.Transaction;
import com.basis.domain.event.AverageCostElection;
import com.basis.domain.event.Buy;
import com.basis.domain.event.CashDividend;
import com.basis.domain.event.Fee;
import com.basis.domain.event.InterestEarned;
import com.basis.domain.event.LedgerEvent;
import com.basis.domain.event.OpeningBalance;
import com.basis.domain.event.ReverseSplit;
import com.basis.domain.event.Sell;
import com.basis.domain.event.SpinOff;
import com.basis.domain.event.Split;
import com.basis.domain.event.StockDividend;
import com.basis.domain.event.Transfer;
import com.basis.domain.Account;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every event the sealed hierarchy declares, put through one ledger in a plausible order.
 *
 * <p>Replaces the tests that used to assert the unimplemented events threw. There are none
 * left to throw, so what is worth asserting now is the opposite: that nothing in the
 * hierarchy is unreachable, and that a realistic sequence of all of them leaves a ledger
 * whose every transaction balances.
 *
 * <p>The coverage check reads the permitted subclasses off the sealed interface rather than
 * counting a hardcoded number, so a new event fails here until someone decides what it does.
 * That has already earned itself: adding AverageCostElection broke this test before it broke
 * anything else. The compiler forces the handler's switch to be exhaustive; this forces the
 * suite to be.
 */
class EveryEventIsHandledTest {

    private static final Commodity NEWCO = Commodity.equity("NEWCO");
    private static final Commodity VTSAX = Commodity.mutualFund("VTSAX");

    @Test
    @DisplayName("every event the hierarchy permits runs through one ledger and balances")
    void everyEventIsHandledAndBalances() {
        Ledger ledger = new Ledger();
        List<LedgerEvent> events = oneOfEach();
        Set<Class<?>> exercised = new LinkedHashSet<>();

        for (LedgerEvent event : events) {
            Transaction txn = ledger.record(event);
            exercised.add(event.getClass());
            assertThat(BalanceChecker.isBalanced(txn.postings()))
                    .as("%s on %s balances at cost", event.type(), event.date())
                    .isTrue();
            assertThat(txn.postings()).as("%s produced postings", event.type()).isNotEmpty();
        }

        assertThat(exercised)
                .as("every event the sealed hierarchy permits is exercised here")
                .containsExactlyInAnyOrder(LedgerEvent.class.getPermittedSubclasses());
    }

    @Test
    @DisplayName("the whole sequence conserves cash against an independent count")
    void cashIsConservedAcrossEveryEventType() {
        Ledger ledger = new Ledger();
        for (LedgerEvent event : oneOfEach()) {
            ledger.record(event);
        }

        // Opening 100000, buy 100 AAPL at 10.00 plus 1.00, buy 100 VTSAX at 10.00,
        // dividend 40.00 less 6.00 withheld, sell 50 at 30.00 less 2.00, fee 7.50,
        // 250.00 transferred out to Schwab, and interest of 12.00 less 2.00 withheld.
        // The corporate actions settle no cash at all.
        Money expected = usd("100000.00")
                .minus(usd("1001.00"))
                .minus(usd("1000.00"))
                .plus(usd("34.00"))
                .plus(usd("1498.00"))
                .minus(usd("7.50"))
                .minus(usd("250.00"))
                .plus(usd("10.00"));

        assertThat(ledger.state().cash(LedgerAccounts.cash(IBKR), USD)).isEqualTo(expected);
        assertThat(ledger.state().cash(LedgerAccounts.cash(SCHWAB), USD)).isEqualTo(usd("250.00"));
    }

    /**
     * One of each event, ordered so each one has something to act on: cash before a
     * purchase, a position before a distribution, a corporate action before the sale that
     * has to price its basis.
     */
    private static List<LedgerEvent> oneOfEach() {
        List<LedgerEvent> events = new ArrayList<>();
        Account broker = IBKR;

        events.add(new OpeningBalance(JAN_15, broker, "e1", row("e1"),
                Commodity.of(USD), qty("100000"), null));
        events.add(new Buy(JAN_15.plusDays(1), broker, "e2", row("e2"), AAPL,
                qty("100"), Price.of("10.00", USD), usd("1.00")));
        events.add(new Buy(JAN_15.plusDays(1), broker, "e2b", row("e2b"), VTSAX,
                qty("100"), Price.of("10.00", USD), usd("0.00")));
        events.add(new CashDividend(JAN_15.plusDays(2), broker, "e3", row("e3"), AAPL,
                usd("40.00"), usd("6.00")));
        events.add(new StockDividend(JAN_15.plusDays(3), broker, "e4", row("e4"), AAPL, qty("20")));
        events.add(new Split(JAN_15.plusDays(4), broker, "e5", row("e5"), AAPL, 2, 1));
        events.add(new ReverseSplit(JAN_15.plusDays(5), broker, "e6", row("e6"), AAPL, 1, 2));
        events.add(new SpinOff(JAN_15.plusDays(6), broker, "e7", row("e7"), AAPL, NEWCO,
                qty("0.25"), new BigDecimal("0.20")));
        events.add(new Transfer(JAN_15.plusDays(7), broker, SCHWAB, "e8", row("e8"),
                Commodity.of(USD), qty("250"), LotSelectionMethod.FIFO));
        events.add(new Sell(JAN_15.plusDays(8), broker, "e9", row("e9"), AAPL,
                qty("50"), Price.of("30.00", USD), usd("2.00"), LotSelectionMethod.FIFO, List.of()));
        events.add(new Fee(JAN_15.plusDays(9), broker, "e10", row("e10"),
                Account.of("Expenses:Fees:Account"), usd("7.50")));
        // Permitted for a fund and for nothing else, so the fund bought above is what makes
        // this event reachable at all.
        events.add(new AverageCostElection(JAN_15.plusDays(10), broker, "e11", row("e11"), VTSAX));
        // Names no security, which is the whole reason it is not a CashDividend.
        events.add(new InterestEarned(JAN_15.plusDays(11), broker, "e12", row("e12"),
                usd("12.00"), usd("2.00")));
        return events;
    }

    private static String row(String ref) {
        return "{\"ref\":\"" + ref + "\"}";
    }
}
