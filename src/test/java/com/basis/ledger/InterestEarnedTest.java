package com.basis.ledger;

import static com.basis.support.Fixtures.IBKR;
import static com.basis.support.Fixtures.JAN_15;
import static com.basis.support.Fixtures.USD;
import static com.basis.support.Fixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basis.domain.Money;
import com.basis.domain.Posting;
import com.basis.domain.Transaction;
import com.basis.domain.event.InterestEarned;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Income that comes from no security.
 *
 * <p>The ledger could always book a dividend and always book a fee, and until this event
 * existed it could not book interest at all: a cash sweep paying out, a bond coupon, a
 * balance earning its keep. A statement row saying INTEREST EARNED stopped the import,
 * correctly, because there was nothing honest to turn it into.
 */
class InterestEarnedTest {

    @Test
    @DisplayName("interest lands in cash and is booked to its own income account")
    void interestCreditsCashAndIncome() {
        Transaction txn = new Ledger().record(interest(usd("12.00"), usd("0.00")));

        assertThat(weightOf(txn, LedgerAccounts.cash(IBKR))).isEqualTo(usd("12.00"));
        assertThat(weightOf(txn, LedgerAccounts.INTEREST_INCOME)).isEqualTo(usd("-12.00"));
        assertThat(BalanceChecker.isBalanced(txn.postings())).isTrue();
    }

    @Test
    @DisplayName("interest is not filed under dividends, which are taxed differently")
    void interestIsNotADividend() {
        Transaction txn = new Ledger().record(interest(usd("12.00"), usd("0.00")));

        assertThat(txn.postings().stream().map(posting -> posting.account().name()))
                .as("a question about dividend income must not find interest inside the answer")
                .noneMatch(name -> name.contains("Dividend"));
        assertThat(LedgerAccounts.INTEREST_INCOME.name()).isEqualTo("Income:Interest");
    }

    @Test
    @DisplayName("withheld tax is its own leg, so the shortfall in cash is explainable")
    void withholdingIsVisibleRatherThanNetted() {
        Transaction txn = new Ledger().record(interest(usd("12.00"), usd("2.00")));

        assertThat(weightOf(txn, LedgerAccounts.INTEREST_INCOME))
                .as("the income is what was earned, not what survived")
                .isEqualTo(usd("-12.00"));
        assertThat(weightOf(txn, LedgerAccounts.WITHHOLDING_TAX)).isEqualTo(usd("2.00"));
        assertThat(weightOf(txn, LedgerAccounts.cash(IBKR))).isEqualTo(usd("10.00"));
    }

    @Test
    @DisplayName("interest that is not income is refused rather than booked as negative income")
    void negativeInterestIsRefused() {
        assertThatThrownBy(() -> interest(usd("-1.00"), usd("0.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fee");
    }

    @Test
    @DisplayName("withholding larger than the interest is refused")
    void withholdingCannotExceedTheInterest() {
        assertThatThrownBy(() -> interest(usd("2.00"), usd("3.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds");
    }

    private static InterestEarned interest(Money gross, Money withheld) {
        return new InterestEarned(JAN_15, IBKR, "i1", "{\"ref\":\"i1\"}", gross, withheld);
    }

    private static Money weightOf(Transaction txn, com.basis.domain.Account account) {
        Money total = Money.zero(USD);
        for (Posting posting : txn.postings()) {
            if (posting.account().equals(account)) {
                total = total.plus(posting.weight());
            }
        }
        return total;
    }
}
