package com.basis.ledger.lot;

import com.basis.domain.Lot;
import com.basis.domain.LotSelectionMethod;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Currency;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Highest cost first, which minimises the realized gain on a disposal.
 *
 * <p>Refuses to run when the open lots are not all in one currency: ranking a lot
 * bought at 90 EUR against one bought at 100 USD is meaningless without a rate, and
 * picking arbitrarily would quietly minimise the wrong number.
 */
public final class HifoLotSelection extends OrderedLotSelection {

    @Override
    public LotSelectionMethod method() {
        return LotSelectionMethod.HIFO;
    }

    @Override
    Comparator<Lot> order(LotSelectionRequest request) {
        requireSingleCurrency(request);
        return Comparator.comparing((Lot lot) -> lot.unitCost().value(), Comparator.<BigDecimal>reverseOrder())
                .thenComparing(Lot::acquisitionDate)
                .thenComparing(byLotId());
    }

    private static void requireSingleCurrency(LotSelectionRequest request) {
        Set<Currency> currencies = request.openLots().stream()
                .map(lot -> lot.unitCost().currency())
                .collect(Collectors.toSet());
        if (currencies.size() > 1) {
            throw new IllegalStateException("cannot rank " + request.commodity() + " lots by cost across currencies "
                    + currencies.stream().map(Currency::getCurrencyCode).sorted().collect(Collectors.joining(", "))
                    + " without an exchange rate");
        }
    }
}
