package com.basis.ledger.lot;

import com.basis.domain.Lot;
import com.basis.domain.LotSelectionMethod;
import java.util.Comparator;

/** Oldest acquisition first. The US default when a taxpayer identifies nothing. */
public final class FifoLotSelection extends OrderedLotSelection {

    @Override
    public LotSelectionMethod method() {
        return LotSelectionMethod.FIFO;
    }

    @Override
    Comparator<Lot> order(LotSelectionRequest request) {
        return Comparator.comparing(Lot::acquisitionDate).thenComparing(byLotId());
    }
}
