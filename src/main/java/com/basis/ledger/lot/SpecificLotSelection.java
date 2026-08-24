package com.basis.ledger.lot;

import com.basis.domain.Lot;
import com.basis.domain.LotId;
import com.basis.domain.LotSelectionMethod;
import com.basis.domain.Quantity;
import com.basis.domain.SpecificLotRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Consumes exactly the lots the disposal named, in the order it named them.
 *
 * <p>The named quantities must sum to the disposal quantity. Partially specifying a
 * disposal and letting a fallback method cover the rest is not offered: a taxpayer who
 * identified some shares and not others has not made a determinate election, and
 * guessing the remainder is exactly the kind of silent choice this project refuses.
 */
public final class SpecificLotSelection implements LotSelectionStrategy {

    @Override
    public LotSelectionMethod method() {
        return LotSelectionMethod.SPECIFIC_LOT;
    }

    @Override
    public List<LotConsumption> select(LotSelectionRequest request) {
        Map<LotId, Lot> open = new LinkedHashMap<>();
        for (Lot lot : request.openLots()) {
            if (lot.isOpen()) {
                open.put(lot.id(), lot);
            }
        }

        List<LotConsumption> consumed = new ArrayList<>();
        // A lot may legitimately be named more than once, so what matters is the running
        // total taken from it, not each request in isolation.
        Map<LotId, Quantity> takenPerLot = new HashMap<>();
        Quantity total = Quantity.ZERO;

        for (SpecificLotRequest named : request.namedLots()) {
            Lot lot = open.get(named.lotId());
            if (lot == null) {
                throw UnknownLotException.of(request.account(), request.commodity(), named.lotId());
            }
            Quantity taken = takenPerLot.getOrDefault(named.lotId(), Quantity.ZERO).plus(named.quantity());
            if (taken.compareTo(lot.remainingQuantity()) > 0) {
                throw new InsufficientLotsException("disposal names " + taken + " of lot " + named.lotId()
                        + " but only " + lot.remainingQuantity() + " " + request.commodity() + " is open in it");
            }
            takenPerLot.put(named.lotId(), taken);
            consumed.add(new LotConsumption(lot, named.quantity()));
            total = total.plus(named.quantity());
        }

        if (!total.equals(request.quantity())) {
            throw new LotSelectionException("a specific lot disposal of " + request.quantity() + " "
                    + request.commodity() + " named lots totalling " + total
                    + ". Naming part of a disposal is not a determinate election, so the remainder"
                    + " is not filled in by any fallback method.");
        }
        return List.copyOf(consumed);
    }
}
