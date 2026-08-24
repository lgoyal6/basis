package com.basis.ledger;

import com.basis.domain.Transaction;
import com.basis.domain.event.LedgerEvent;

/**
 * Turns an event into a balanced transaction. The only place in the system where
 * arithmetic on a trade happens.
 *
 * <p>Dispatch is an exhaustive switch over the sealed hierarchy, so a new corporate
 * action cannot be added without this class refusing to compile. The events week 1 does
 * not own are refused loudly at runtime rather than quietly mishandled.
 */
public final class LedgerEventHandler {

    /**
     * @param lots open lots to draw a disposal from, read only
     * @throws UnsupportedOperationException for events a later week owns
     */
    public Transaction toTransaction(LedgerEvent event, LotBook lots) {
        throw new UnsupportedOperationException("not implemented");
    }
}
