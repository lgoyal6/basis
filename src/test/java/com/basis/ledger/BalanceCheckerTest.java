package com.basis.ledger;

import static com.basis.support.Fixtures.AAPL;
import static com.basis.support.Fixtures.EUR;
import static com.basis.support.Fixtures.IBKR_CASH;
import static com.basis.support.Fixtures.JAN_15;
import static com.basis.support.Fixtures.USD;
import static com.basis.support.Fixtures.price;
import static com.basis.support.Fixtures.qty;
import static com.basis.support.Fixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Cost;
import com.basis.domain.IdempotencyKey;
import com.basis.domain.LotId;
import com.basis.domain.Money;
import com.basis.domain.Posting;
import com.basis.domain.Price;
import com.basis.domain.Transaction;
import com.basis.domain.TxnId;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The balance checker, pointed at transactions built by hand to be wrong.
 *
 * <p>This is why {@code Transaction} does not enforce balance in its constructor. If it
 * did, none of the counterexamples below could be constructed, and the check would be
 * asserting something the type system already guaranteed rather than checking that
 * {@code Posting.weight()} rounds the way it is supposed to. See
 * docs/ARCHITECTURE.md section 12.
 */
class BalanceCheckerTest {

    private static final Account AAPL_HOLDING = Account.of("Assets:Broker:IBKR:AAPL");

    @Test
    @DisplayName("a transaction that does not sum to zero is rejected, and says by how much")
    void rejectsUnbalanced() {
        Transaction txn = transaction(
                security(AAPL_HOLDING, AAPL, "10", "150.00"),
                Posting.cash(IBKR_CASH, usd("-1499.00")));

        assertThatThrownBy(() -> BalanceChecker.requireBalanced(txn))
                .isInstanceOf(UnbalancedTransactionException.class)
                .hasMessageContaining("1.00 USD");
    }

    @Test
    @DisplayName("one cent out is still out")
    void rejectsASingleCent() {
        Transaction txn = transaction(
                security(AAPL_HOLDING, AAPL, "10", "150.00"),
                Posting.cash(IBKR_CASH, usd("-1500.01")));

        assertThat(BalanceChecker.isBalanced(txn.postings())).isFalse();
        assertThat(BalanceChecker.residual(txn.postings(), USD)).isEqualTo(Money.ofMinor(-1, USD));
    }

    @Test
    @DisplayName("balance is required per currency, so USD cannot be settled with EUR")
    void doesNotLetOneCurrencyBalanceAnother() {
        Transaction txn = transaction(
                Posting.cash(IBKR_CASH, usd("-100.00")),
                Posting.cash(Account.of("Assets:Broker:IBKR:CashEUR"), Money.of(new BigDecimal("100.00"), EUR)));

        assertThat(BalanceChecker.isBalanced(txn.postings())).isFalse();
        assertThat(BalanceChecker.residual(txn.postings(), USD)).isEqualTo(usd("-100.00"));
        assertThat(BalanceChecker.residual(txn.postings(), EUR)).isEqualTo(Money.of(new BigDecimal("100.00"), EUR));
    }

    @Test
    @DisplayName("a currency that does not appear has a zero residual, not an absent one")
    void absentCurrencyResidualIsZero() {
        List<Posting> postings = List.of(
                security(AAPL_HOLDING, AAPL, "10", "150.00"),
                Posting.cash(IBKR_CASH, usd("-1500.00")));

        assertThat(BalanceChecker.residual(postings, EUR)).isEqualTo(Money.zero(EUR));
        assertThat(BalanceChecker.isBalanced(postings)).isTrue();
    }

    @Test
    @DisplayName("weight is rounded per posting, so a fractional share weighs a whole number of cents")
    void weightRoundsPerPostingHalfEven() {
        // 0.33333333 shares at 150.00 is 49.9999995, which is a half cent exactly.
        // HALF_EVEN takes it to 50.00 rather than 50.01, and the weight is whole cents.
        Posting posting = security(AAPL_HOLDING, AAPL, "0.33333333", "150.00");

        assertThat(posting.weight()).isEqualTo(usd("50.00"));
    }

    @Test
    @DisplayName("a cash posting that is not whole cents is refused rather than rounded")
    void inexactCashIsRefused() {
        Posting fractionalCent = new Posting(IBKR_CASH, Commodity.of(USD), qty("1.005"), null);

        assertThatThrownBy(fractionalCent::weight)
                .isInstanceOf(ArithmeticException.class)
                .hasMessageContaining("not exact");
    }

    private static Posting security(Account account, Commodity commodity, String quantity, String unitCost) {
        Price cost = price(unitCost);
        return Posting.security(account, commodity, qty(quantity),
                new Cost(LotId.of("lot-a1"), cost, JAN_15));
    }

    private static Transaction transaction(Posting... postings) {
        return new Transaction(TxnId.random(), JAN_15, "Test", "hand built",
                List.of(postings), IdempotencyKey.of("test"), "{}");
    }
}
