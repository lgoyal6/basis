package com.basis.ledger.lot;

import com.basis.domain.LotSelectionMethod;
import java.util.List;

/**
 * Average cost basis. Restricted, and not implemented.
 *
 * <p>Two different refusals, because they are two different facts:
 *
 * <ul>
 *   <li>the commodity is not eligible: {@link AverageCostNotPermittedException}. US
 *       rules allow average cost only for mutual fund shares and certain dividend
 *       reinvestment plans, so an equity or an ETF is refused permanently and no later
 *       week can change that.
 *   <li>the commodity is eligible: {@code UnsupportedOperationException}. The method
 *       itself is simply not written yet.
 * </ul>
 *
 * <p>Collapsing these into one exception would mean that implementing average cost for
 * mutual funds would silently make it available for equities too.
 */
public final class AverageCostLotSelection implements LotSelectionStrategy {

    @Override
    public LotSelectionMethod method() {
        return LotSelectionMethod.AVERAGE_COST;
    }

    @Override
    public List<LotConsumption> select(LotSelectionRequest request) {
        if (!request.commodity().commodityClass().isAverageCostEligible()) {
            throw AverageCostNotPermittedException.of(request.commodity());
        }
        throw new UnsupportedOperationException("average cost basis for " + request.commodity()
                + " is eligible but not implemented. Week 1 implements FIFO, LIFO, HIFO and specific lot.");
    }
}
