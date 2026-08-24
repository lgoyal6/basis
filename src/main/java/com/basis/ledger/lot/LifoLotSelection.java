package com.basis.ledger.lot;

import com.basis.domain.Lot;
import com.basis.domain.LotSelectionMethod;
import java.util.Comparator;

/**
 * Newest acquisition first.
 *
 * <p>The date is reversed but the lot id tiebreak is not, so that two lots acquired on
 * the same day are still consumed in one fixed order rather than the reverse of FIFO's.
 */
public final class LifoLotSelection extends OrderedLotSelection {

    @Override
    public LotSelectionMethod method() {
        return LotSelectionMethod.LIFO;
    }

    @Override
    Comparator<Lot> order(LotSelectionRequest request) {
        return Comparator.comparing(Lot::acquisitionDate, Comparator.reverseOrder()).thenComparing(byLotId());
    }
}
