package com.basis.ledger.lot;

import com.basis.domain.Commodity;

/**
 * Average cost basis was requested for a commodity that is not eligible for it.
 *
 * <p>US rules permit average cost only for mutual fund shares and certain dividend
 * reinvestment plans. Ordinary equities and ETFs are never eligible, whatever a broker
 * offers in its interface.
 *
 * <p>Deliberately not the same exception as the one saying average cost is
 * unimplemented. This one is a permanent rule about the domain; that one is a
 * statement about the calendar. Collapsing them would mean a later week could
 * accidentally make an illegal basis method legal by implementing it.
 */
public class AverageCostNotPermittedException extends LotSelectionException {

    private static final long serialVersionUID = 1L;

    public AverageCostNotPermittedException(String message) {
        super(message);
    }

    static AverageCostNotPermittedException of(Commodity commodity) {
        return new AverageCostNotPermittedException("average cost basis is not permitted for " + commodity
                + " (" + commodity.commodityClass() + "). US rules allow it only for mutual fund shares and"
                + " certain dividend reinvestment plans.");
    }
}
