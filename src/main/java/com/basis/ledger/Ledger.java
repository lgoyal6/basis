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
        throw new UnsupportedOperationException("not implemented");
    }

    public LedgerState state() {
        return state;
    }

    LedgerEventHandler handler() {
        return handler;
    }
}
