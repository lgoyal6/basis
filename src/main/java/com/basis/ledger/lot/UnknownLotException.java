package com.basis.ledger.lot;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.LotId;

/**
 * A specific lot disposal named a lot that does not exist, or is already closed, in
 * the account it was named for. Distinct from {@link InsufficientLotsException}: the
 * holding may be perfectly adequate and still not contain the lot that was asked for.
 */
public class UnknownLotException extends LotSelectionException {

    private static final long serialVersionUID = 1L;

    public UnknownLotException(String message) {
        super(message);
    }

    static UnknownLotException of(Account account, Commodity commodity, LotId lotId) {
        return new UnknownLotException(
                "no open lot " + lotId + " of " + commodity + " in " + account);
    }
}
