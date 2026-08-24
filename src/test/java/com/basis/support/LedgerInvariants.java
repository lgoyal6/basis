package com.basis.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Lot;
import com.basis.domain.LotId;
import com.basis.domain.Money;
import com.basis.domain.Posting;
import com.basis.domain.Quantity;
import com.basis.domain.Transaction;
import com.basis.ledger.BalanceChecker;
import com.basis.ledger.LedgerAccounts;
import com.basis.ledger.LedgerState;
import com.basis.ledger.PositionKey;
import com.basis.ledger.RealizedGain;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Invariants 1 to 5, asserted against a ledger from outside it.
 *
 * <p>Each check recomputes its expectation from the recorded postings rather than asking
 * the ledger what it thinks. A check that reads the same field it is verifying proves
 * nothing, so lot conservation walks every posting ever recorded and re-derives what
 * each lot should hold, and the proceeds identity is asserted between three numbers that
 * came from three different subsets of the postings.
 */
public final class LedgerInvariants {

    private LedgerInvariants() {
    }

    /** Invariants 1 to 5, all of them, against everything recorded so far. */
    public static void assertAllHold(LedgerState state, List<Transaction> recorded) {
        assertTransactionsBalance(recorded);
        assertLotsAreConserved(state, recorded);
        assertPositionsMatchOpenLots(state);
        assertBasisMatchesOpenLots(state);
        assertProceedsIdentity(state);
    }

    /** Invariant 1: postings sum to zero at cost, for every transaction. */
    public static void assertTransactionsBalance(List<Transaction> recorded) {
        for (Transaction txn : recorded) {
            assertThat(BalanceChecker.isBalanced(txn.postings()))
                    .as("invariant 1, transaction balances at cost: %s", txn)
                    .isTrue();
        }
    }

    /**
     * Invariant 2: remaining equals acquired minus disposed, and is never negative.
     *
     * <p>Acquired and disposed are re-derived here by walking every security posting ever
     * recorded, so this compares the lot table against the postings rather than against
     * itself.
     */
    public static void assertLotsAreConserved(LedgerState state, List<Transaction> recorded) {
        Map<LotId, Quantity> acquired = new HashMap<>();
        Map<LotId, Quantity> disposed = new HashMap<>();
        for (Transaction txn : recorded) {
            for (Posting posting : txn.postings()) {
                if (!posting.hasCost()) {
                    continue;
                }
                LotId lotId = posting.cost().lotId();
                if (posting.quantity().isPositive()) {
                    acquired.merge(lotId, posting.quantity(), Quantity::plus);
                } else {
                    disposed.merge(lotId, posting.quantity().negate(), Quantity::plus);
                }
            }
        }

        Set<LotId> seen = new HashSet<>();
        for (Lot lot : state.allLots()) {
            seen.add(lot.id());
            Quantity wasAcquired = acquired.getOrDefault(lot.id(), Quantity.ZERO);
            Quantity wasDisposed = disposed.getOrDefault(lot.id(), Quantity.ZERO);

            assertThat(lot.originalQuantity())
                    .as("invariant 2, lot %s acquired quantity matches its postings", lot.id())
                    .isEqualTo(wasAcquired);
            assertThat(lot.remainingQuantity())
                    .as("invariant 2, lot %s remaining equals acquired minus disposed", lot.id())
                    .isEqualTo(wasAcquired.minus(wasDisposed));
            assertThat(lot.remainingQuantity().isNegative())
                    .as("invariant 2, lot %s remaining is not negative", lot.id())
                    .isFalse();
        }
        assertThat(seen)
                .as("invariant 2, every lot named by a posting exists in lot state")
                .containsAll(acquired.keySet());
    }

    /**
     * Invariant 3: a holding's quantity equals the sum of its open lots' quantities.
     *
     * <p>Checked in both directions. A position that reaches zero is dropped rather than
     * kept as a zero row, so walking positions alone would miss the failure that matters
     * most: a lot still holding shares after the position it belongs to has disappeared.
     */
    public static void assertPositionsMatchOpenLots(LedgerState state) {
        Map<PositionKey, Quantity> fromLots = new HashMap<>();
        for (Lot lot : state.allLots()) {
            if (!lot.isOpen()) {
                continue;
            }
            fromLots.merge(new PositionKey(lot.account(), lot.commodity()),
                    lot.remainingQuantity(), Quantity::plus);
        }

        for (Map.Entry<PositionKey, Quantity> entry : state.positions().entrySet()) {
            if (entry.getKey().commodity().isCash()) {
                continue;
            }
            assertThat(entry.getValue())
                    .as("invariant 3, position %s equals the sum of its open lots", entry.getKey())
                    .isEqualTo(fromLots.getOrDefault(entry.getKey(), Quantity.ZERO));
        }

        for (Map.Entry<PositionKey, Quantity> entry : fromLots.entrySet()) {
            assertThat(state.position(entry.getKey().account(), entry.getKey().commodity()))
                    .as("invariant 3, open lots in %s are matched by a position of the same size",
                            entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    /** Invariant 4: total basis equals the sum over open lots of quantity times unit cost. */
    public static void assertBasisMatchesOpenLots(LedgerState state) {
        for (Map.Entry<PositionKey, Quantity> entry : state.positions().entrySet()) {
            PositionKey key = entry.getKey();
            if (key.commodity().isCash()) {
                continue;
            }
            List<Lot> open = state.openLots(key.account(), key.commodity());
            if (open.isEmpty()) {
                continue;
            }
            Currency currency = open.get(0).unitCost().currency();
            Money expected = Money.zero(currency);
            for (Lot lot : open) {
                expected = expected.plus(Money.round(
                        lot.remainingQuantity().multiplyBy(lot.unitCost()), currency));
            }
            assertThat(state.openBasis(key.account(), key.commodity(), currency))
                    .as("invariant 4, basis of %s equals the sum over its open lots", key)
                    .isEqualTo(expected);
        }
    }

    /** Invariant 5: proceeds minus basis equals the realized gain, exactly, in minor units. */
    public static void assertProceedsIdentity(LedgerState state) {
        for (RealizedGain gain : state.realizedGains()) {
            assertThat(gain.proceeds().minus(gain.basis()))
                    .as("invariant 5, proceeds minus basis equals gain for %s", gain.txnId())
                    .isEqualTo(gain.gain());
        }
    }

    /**
     * Invariant 6: no cash is created inside the system.
     *
     * <p>{@code expected} has to be accumulated by the caller from the events, entirely
     * outside the ledger. Deriving it from the postings would make this a restatement of
     * invariant 1.
     *
     * <p>Checked per account and not only in total, because a bug that moves cash to the
     * wrong account conserves the total perfectly. Also checked in the other direction: no
     * cash account the ledger knows about may be missing from the expectation, or a
     * transfer into an account nobody is watching would go unnoticed.
     */
    public static void assertCashIsConserved(LedgerState state, Map<Account, Money> expected) {
        for (Map.Entry<Account, Money> entry : expected.entrySet()) {
            Account cashAccount = entry.getKey();
            Money amount = entry.getValue();
            assertThat(state.cash(cashAccount, amount.currency()))
                    .as("invariant 6, cash in %s is exactly what went in and out", cashAccount)
                    .isEqualTo(amount);
            assertThat(state.position(cashAccount, Commodity.of(amount.currency())).value())
                    .as("invariant 6, the cash position in %s agrees with the cash balance", cashAccount)
                    .isEqualByComparingTo(amount.toMajorUnits());
        }

        // Scoped to accounts that hold cash, not to every account with a currency position.
        // The contra accounts a distribution or a purchase books against, such as
        // Income:Dividends:AAPL or Equity:Opening-Balances, carry currency positions by
        // design and are not balances anyone holds.
        for (Map.Entry<PositionKey, Quantity> entry : state.positions().entrySet()) {
            if (!entry.getKey().commodity().isCash()) {
                continue;
            }
            if (!entry.getKey().account().leaf().equals(LedgerAccounts.CASH_LEAF)) {
                continue;
            }
            assertThat(expected)
                    .as("invariant 6, cash landed in %s, which nothing expected to be holding any",
                            entry.getKey().account())
                    .containsKey(entry.getKey().account());
        }
    }
}
