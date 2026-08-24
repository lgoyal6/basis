package com.basis.ledger;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Cost;
import com.basis.domain.IdempotencyKey;
import com.basis.domain.Lot;
import com.basis.domain.LotId;
import com.basis.domain.Money;
import com.basis.domain.Posting;
import com.basis.domain.Price;
import com.basis.domain.Quantity;
import com.basis.domain.event.LedgerEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Restates a position's share count without changing what it is worth.
 *
 * <p>Shared by every corporate action that multiplies a share count: a forward split, a
 * reverse split, and a stock dividend, which is a split expressed as an added quantity
 * rather than as a ratio. One arithmetic, so the three cannot drift apart.
 *
 * <p>Each open lot is disposed of in full and reopened at the new quantity and a restated
 * unit cost, carrying its original acquisition date. Carrying the date is the whole point:
 * a split does not restart a holding period, and dating the new lot to the split would
 * turn a long term gain into a short term one on every later disposal.
 *
 * <p>The restated unit cost is derived from the lot's basis rather than from the ratio.
 * Dividing the old unit cost by the ratio looks equivalent and is not: it rounds the cost
 * first and multiplies the error by the new share count, so a large position loses basis
 * for no reason anyone can point at. Deriving cost as basis divided by new quantity puts
 * the rounding where it does the least damage.
 *
 * <p>Even so, six decimal places of unit cost cannot always reproduce a basis to the cent.
 * Above roughly ten thousand shares the residue can exceed half a cent, and it has to go
 * somewhere. It goes to {@link LedgerAccounts#ROUNDING}, as the transaction's plug, which
 * makes it visible and queryable instead of silent. See docs/ARCHITECTURE.md section 19.
 */
final class Relotting {

    private Relotting() {
    }

    /**
     * Builds the postings that restate {@code lots} by {@code numerator} for
     * {@code denominator}.
     *
     * @throws IllegalStateException if the position holds nothing to restate
     */
    static List<Posting> restate(
            LedgerEvent event,
            Account holding,
            Commodity commodity,
            List<Lot> lots,
            long numerator,
            long denominator) {
        if (lots.isEmpty()) {
            throw new IllegalStateException(event.type() + " on " + commodity + " in " + holding
                    + " has no open lots to restate. A corporate action on a position that was never"
                    + " held is a break, not a transaction.");
        }

        List<Posting> postings = new ArrayList<>();
        for (Lot lot : lots) {
            Quantity restated = scale(lot.remainingQuantity(), numerator, denominator);
            Money basis = lot.remainingBasis();

            postings.add(Posting.security(holding, commodity, lot.remainingQuantity().negate(), lot.cost()));
            if (restated.isZero()) {
                // The lot rounded away entirely. Its basis becomes residue rather than
                // vanishing, which is what stops a reverse split from quietly destroying it.
                continue;
            }
            postings.add(Posting.security(holding, commodity, restated,
                    new Cost(derivedLotId(event, lot.id()), unitCostFor(basis, restated), lot.acquisitionDate())));
        }
        return List.copyOf(postings);
    }

    /** New quantity, computed in one division so the ratio is not rounded twice. */
    private static Quantity scale(Quantity quantity, long numerator, long denominator) {
        return Quantity.of(quantity.value()
                .multiply(BigDecimal.valueOf(numerator))
                .divide(BigDecimal.valueOf(denominator), Quantity.SCALE, RoundingMode.HALF_EVEN));
    }

    /** Basis per restated share, which is the quantity that has to survive, not the ratio. */
    private static Price unitCostFor(Money basis, Quantity restated) {
        return Price.of(
                basis.toMajorUnits().divide(restated.value(), Price.SCALE, RoundingMode.HALF_EVEN),
                basis.currency());
    }

    /**
     * Stable across replays, fixed in length however many corporate actions a position
     * survives, and traceable to the lot it replaced.
     */
    static LotId derivedLotId(LedgerEvent event, LotId source) {
        return LotId.of(IdempotencyKey.of(event.idempotencyKey().toString(), source.value()).toString());
    }
}
