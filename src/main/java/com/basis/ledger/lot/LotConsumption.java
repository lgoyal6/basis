package com.basis.ledger.lot;

import com.basis.domain.Lot;
import com.basis.domain.Money;
import com.basis.domain.Quantity;
import java.util.Objects;

/** How much of one lot a disposal consumes. */
public record LotConsumption(Lot lot, Quantity quantity) {

    public LotConsumption {
        Objects.requireNonNull(lot, "lot");
        Objects.requireNonNull(quantity, "quantity");
        if (!quantity.isPositive()) {
            throw new IllegalArgumentException("consumed quantity must be positive, was " + quantity);
        }
        if (quantity.compareTo(lot.remainingQuantity()) > 0) {
            throw new IllegalArgumentException("cannot consume " + quantity + " from lot " + lot.id()
                    + " with " + lot.remainingQuantity() + " remaining");
        }
    }

    /**
     * The basis leaving the ledger with this consumption, rounded exactly the way the
     * disposal posting's weight is rounded, so the two cannot disagree by a cent.
     */
    public Money basis() {
        return Money.round(quantity.multiplyBy(lot.unitCost()), lot.unitCost().currency());
    }
}
