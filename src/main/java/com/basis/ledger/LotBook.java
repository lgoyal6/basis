package com.basis.ledger;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Lot;
import java.util.List;

/**
 * Read side of lot state: what is still open in a holding.
 *
 * <p>Narrow on purpose. The event handler needs to see open lots to build a disposal
 * and nothing else, so this is what it is given, rather than the whole ledger.
 */
public interface LotBook {

    /** Open lots in the holding, in acquisition then lot id order. Never null, possibly empty. */
    List<Lot> openLots(Account account, Commodity commodity);
}
