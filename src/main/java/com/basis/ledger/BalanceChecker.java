package com.basis.ledger;

import com.basis.domain.Money;
import com.basis.domain.Posting;
import com.basis.domain.Transaction;
import java.util.Currency;
import java.util.List;
import java.util.Map;

/**
 * Invariant 1: for every transaction, the sum of all postings weighted at cost is zero.
 *
 * <p>Checked per currency rather than by requiring one currency per transaction. That
 * is a strict superset of the single currency rule, so it needs no revisiting when FX
 * arrives, and it correctly refuses a transaction that tries to balance USD against
 * EUR without a stated rate.
 *
 * <p>Static and stateless on purpose. There is nothing here to configure, inject or
 * stub, which means there is no way to run the ledger with the check turned off.
 */
public final class BalanceChecker {

    private BalanceChecker() {
    }

    /** Net weight per currency across the given postings. Zero entries are included. */
    public static Map<Currency, Money> residuals(List<Posting> postings) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** The net weight in one currency, zero if that currency does not appear. */
    public static Money residual(List<Posting> postings, Currency currency) {
        throw new UnsupportedOperationException("not implemented");
    }

    public static boolean isBalanced(List<Posting> postings) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** @throws UnbalancedTransactionException if any currency's weights do not sum to zero */
    public static void requireBalanced(Transaction transaction) {
        throw new UnsupportedOperationException("not implemented");
    }
}
