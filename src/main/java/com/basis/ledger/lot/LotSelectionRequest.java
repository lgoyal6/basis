package com.basis.ledger.lot;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Lot;
import com.basis.domain.Quantity;
import com.basis.domain.SpecificLotRequest;
import java.util.List;
import java.util.Objects;

/**
 * Everything a strategy needs to pick lots.
 *
 * <p>The account and commodity are carried even though only specific lot selection
 * reads them, because every failure message a strategy raises has to name the position
 * it failed on. A reconciliation tool whose errors say "insufficient lots" without
 * saying which holding is not worth running.
 *
 * @param namedLots empty except for specific lot disposals
 */
public record LotSelectionRequest(
        Account account,
        Commodity commodity,
        Quantity quantity,
        List<Lot> openLots,
        List<SpecificLotRequest> namedLots) {

    public LotSelectionRequest {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(commodity, "commodity");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(openLots, "openLots");
        Objects.requireNonNull(namedLots, "namedLots");
        openLots = List.copyOf(openLots);
        namedLots = List.copyOf(namedLots);
        if (!quantity.isPositive()) {
            throw new IllegalArgumentException("disposal quantity must be positive, was " + quantity);
        }
    }

    /** Total quantity open across every lot offered. */
    public Quantity availableQuantity() {
        Quantity total = Quantity.ZERO;
        for (Lot lot : openLots) {
            total = total.plus(lot.remainingQuantity());
        }
        return total;
    }
}
