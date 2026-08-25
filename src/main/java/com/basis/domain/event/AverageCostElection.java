package com.basis.domain.event;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Electing average cost basis for a holding.
 *
 * <p>An event rather than a lot selection method, which is the whole design decision here.
 * Average cost is not a way of choosing which lots a sale consumes; it is a restatement of
 * every lot in the position to one pooled unit cost. Modelling it as a selection strategy
 * would mean a disposal weighing at a cost none of its lots actually carry, and the
 * remaining lots keeping their original costs, so the position's basis would drift apart
 * from the sum of its parts on the first partial sale.
 *
 * <p>As an election it is exactly what it says: from this date the position has one cost per
 * share, lots keep their acquisition dates for holding period, and an ordinary FIFO sale
 * afterwards is an average cost sale.
 *
 * <p>Permitted in the US only for mutual fund shares and certain dividend reinvestment
 * plans. The handler refuses it for anything else, which is the same restriction
 * {@code AverageCostLotSelection} has always encoded.
 */
public record AverageCostElection(
        LocalDate date,
        Account account,
        String externalRef,
        String sourceRow,
        Commodity commodity) implements LedgerEvent {

    public AverageCostElection {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(externalRef, "externalRef");
        Objects.requireNonNull(sourceRow, "sourceRow");
        Objects.requireNonNull(commodity, "commodity");
        if (commodity.isCash()) {
            throw new IllegalArgumentException("cash has no cost basis to average: " + commodity);
        }
    }
}
