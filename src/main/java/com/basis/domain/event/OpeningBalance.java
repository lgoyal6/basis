package com.basis.domain.event;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Price;
import com.basis.domain.Quantity;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The starting state of an account: what was already there before the imported
 * history begins. Balanced against {@code Equity:Opening-Balances}, which is what
 * keeps the ledger closed even though the money came from outside it.
 *
 * <p>Serves both cash and securities, because the sealed hierarchy names one opening
 * balance event and not two. A cash opening balance carries a currency commodity and
 * no unit cost; a security opening balance carries a unit cost and opens a lot.
 *
 * @param unitCost the lot's cost basis, required for a security and forbidden for cash
 */
public record OpeningBalance(
        LocalDate date,
        Account account,
        String externalRef,
        String sourceRow,
        Commodity commodity,
        Quantity quantity,
        Price unitCost) implements LedgerEvent {

    public OpeningBalance {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(externalRef, "externalRef");
        Objects.requireNonNull(sourceRow, "sourceRow");
        Objects.requireNonNull(commodity, "commodity");
        Objects.requireNonNull(quantity, "quantity");
        if (commodity.isCash()) {
            if (unitCost != null) {
                throw new IllegalArgumentException("a cash opening balance must not carry a unit cost");
            }
            if (quantity.isZero()) {
                throw new IllegalArgumentException("a cash opening balance of zero says nothing");
            }
        } else {
            Objects.requireNonNull(unitCost, "unitCost is required for a security opening balance");
            if (!quantity.isPositive()) {
                throw new IllegalArgumentException(
                        "a security opening balance must be positive, was " + quantity
                                + ". A short position is not a week 1 concern.");
            }
        }
    }

    public boolean isCash() {
        return commodity.isCash();
    }
}
