package com.basis.ledger.lot;

import com.basis.domain.Lot;
import java.util.Comparator;
import java.util.List;

/**
 * Base for every strategy that works by sorting the open lots and consuming them in
 * order until the disposal is satisfied. FIFO, LIFO and HIFO differ only in the
 * comparator, so the consumption walk is written once.
 */
abstract class OrderedLotSelection implements LotSelectionStrategy {

    /** Must be a total order. See {@link LotSelectionStrategy}. */
    abstract Comparator<Lot> order(LotSelectionRequest request);

    @Override
    public List<LotConsumption> select(LotSelectionRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }

    /** Ascending by lot id. The final tiebreak that makes every order total. */
    static Comparator<Lot> byLotId() {
        return Comparator.comparing(lot -> lot.id().value());
    }
}
