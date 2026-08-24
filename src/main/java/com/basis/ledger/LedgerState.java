package com.basis.ledger;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Lot;
import com.basis.domain.Money;
import com.basis.domain.Posting;
import com.basis.domain.Quantity;
import com.basis.domain.Transaction;
import com.basis.domain.TxnId;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;

/**
 * The derived state: positions, lots and realized gains.
 *
 * <p>Holds nothing that is not recomputable from postings, and is only ever advanced by
 * applying postings. That is what makes it disposable, and it is why the same class
 * serves both the in memory ledger and the database projector: one projection
 * algorithm, two sinks. If the projector had its own copy of this arithmetic, the two
 * could disagree and invariant 7 would be testing the wrong thing.
 *
 * <p>Not thread safe. A ledger is replayed by one writer.
 */
public final class LedgerState implements LotBook {

    /** Advances the state by one transaction, reading only its postings. */
    public void apply(Transaction transaction) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Advances the state by one transaction's postings, in the given order. This is the
     * entry point replay uses, where there is no {@code Transaction} object left, only
     * rows.
     */
    public void applyPostings(TxnId txnId, LocalDate date, List<Posting> postings) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    public List<Lot> openLots(Account account, Commodity commodity) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** Every lot ever opened, open or closed, in lot id order. */
    public List<Lot> allLots() {
        throw new UnsupportedOperationException("not implemented");
    }

    /** Net quantity held, zero if the holding is unknown. */
    public Quantity position(Account account, Commodity commodity) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** Every non zero position, in account then commodity order. */
    public Map<PositionKey, Quantity> positions() {
        throw new UnsupportedOperationException("not implemented");
    }

    /** Realized gains in the order they were realized. */
    public List<RealizedGain> realizedGains() {
        throw new UnsupportedOperationException("not implemented");
    }

    /** Cash held in an account, zero if none. */
    public Money cash(Account account, Currency currency) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** Total basis still held in a holding: the sum over open lots of quantity times unit cost. */
    public Money openBasis(Account account, Commodity commodity, Currency currency) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** Drops everything. Used before a replay. */
    public void clear() {
        throw new UnsupportedOperationException("not implemented");
    }
}
