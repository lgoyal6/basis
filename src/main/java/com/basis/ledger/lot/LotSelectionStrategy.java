package com.basis.ledger.lot;

import com.basis.domain.LotSelectionMethod;
import java.util.List;

/**
 * Picks which acquisition lots a disposal consumes.
 *
 * <p>Every implementation must be a total order over lots. FIFO, LIFO and HIFO all
 * break ties on lot id after their primary key, because two lots acquired on the same
 * day at the same price would otherwise be consumable in either order, and the derived
 * state hash in invariant 7 would only match some of the time. A flaky invariant is
 * worse than no invariant.
 */
public interface LotSelectionStrategy {

    LotSelectionMethod method();

    /**
     * @return the lots to consume and how much of each, in consumption order, summing
     *     exactly to the requested quantity
     * @throws InsufficientLotsException if the open lots do not cover the request
     */
    List<LotConsumption> select(LotSelectionRequest request);
}
