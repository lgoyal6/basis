package com.basis.ledger;

import com.basis.domain.Transaction;
import com.basis.domain.event.LedgerEvent;

/**
 * Records events into a ledger held in memory.
 *
 * <p>Event to transaction, balance check, then state. The balance check is not optional
 * and not injected: there is no way to construct a Ledger that skips it.
 */
public final class Ledger {

    private final LedgerEventHandler handler = new LedgerEventHandler();
    private final LedgerState state = new LedgerState();

    /** Handles the event, verifies it balances, and applies it. */
    public Transaction record(LedgerEvent event) {
        Transaction transaction = handler.toTransaction(event, state);
        // The builder already checked. Checked again here because this is the boundary
        // that guarantees it, and a boundary that trusts its callers is not a boundary.
        BalanceChecker.requireBalanced(transaction);
        state.apply(transaction);
        return transaction;
    }

    public LedgerState state() {
        return state;
    }
}
