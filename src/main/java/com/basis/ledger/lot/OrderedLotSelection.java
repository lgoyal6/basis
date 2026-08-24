package com.basis.ledger.lot;

import com.basis.domain.Lot;
import com.basis.domain.Quantity;
import java.util.ArrayList;
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
        List<Lot> candidates = request.openLots().stream()
                .filter(Lot::isOpen)
                .sorted(order(request))
                .toList();

        Quantity available = Quantity.ZERO;
        for (Lot lot : candidates) {
            available = available.plus(lot.remainingQuantity());
        }
        if (request.quantity().compareTo(available) > 0) {
            throw InsufficientLotsException.of(
                    request.account(), request.commodity(), request.quantity(), available);
        }

        List<LotConsumption> consumed = new ArrayList<>();
        Quantity outstanding = request.quantity();
        for (Lot lot : candidates) {
            if (!outstanding.isPositive()) {
                break;
            }
            Quantity take = outstanding.compareTo(lot.remainingQuantity()) <= 0
                    ? outstanding
                    : lot.remainingQuantity();
            consumed.add(new LotConsumption(lot, take));
            outstanding = outstanding.minus(take);
        }
        if (outstanding.isPositive()) {
            throw new IllegalStateException("selection of " + request.quantity() + " " + request.commodity()
                    + " left " + outstanding + " unallocated despite " + available + " being available."
                    + " The available quantity and the consumption walk disagree.");
        }
        return List.copyOf(consumed);
    }

    /** Ascending by lot id. The final tiebreak that makes every order total. */
    static Comparator<Lot> byLotId() {
        return Comparator.comparing(lot -> lot.id().value());
    }
}
