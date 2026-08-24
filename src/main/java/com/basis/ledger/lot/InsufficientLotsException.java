package com.basis.ledger.lot;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Quantity;

/**
 * A disposal asked for more than the open lots hold.
 *
 * <p>In week 1 this is an error. Once reconciliation lands this becomes a
 * {@code break_record} with a probable cause attached, because the usual reason a
 * sale exceeds the holding is an unapplied split or a missing acquisition, not a
 * genuine short position.
 */
public class InsufficientLotsException extends LotSelectionException {

    private static final long serialVersionUID = 1L;

    public InsufficientLotsException(String message) {
        super(message);
    }

    static InsufficientLotsException of(Account account, Commodity commodity, Quantity requested, Quantity available) {
        return new InsufficientLotsException("cannot dispose " + requested + " " + commodity + " from " + account
                + ": only " + available + " is open across all lots");
    }
}
