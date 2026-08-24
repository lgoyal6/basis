package com.basis.ledger.lot;

import com.basis.domain.LotSelectionMethod;
import java.util.List;

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
        throw new UnsupportedOperationException("not implemented");
    }
}
