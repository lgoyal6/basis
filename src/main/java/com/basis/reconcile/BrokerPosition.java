package com.basis.reconcile;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Money;
import com.basis.domain.Quantity;
import java.util.Objects;

/**
 * A holding as the broker reports it.
 *
 * <p>Deliberately not a {@code Posting} and not a {@code Lot}. This is a claim made by
 * someone else, and the whole job of reconciliation is to keep it separate from what
 * basis computed until the two have been compared.
 *
 * @param reportedBasis what the broker says the position cost, or null when the statement
 *     does not say. Many statements report quantity and market value and nothing else.
 */
public record BrokerPosition(Account account, Commodity commodity, Quantity quantity, Money reportedBasis) {

    public BrokerPosition {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(commodity, "commodity");
        Objects.requireNonNull(quantity, "quantity");
        if (quantity.isNegative()) {
            throw new IllegalArgumentException("a reported position must not be negative, was " + quantity
                    + ". A short position is not something week 4 reconciles.");
        }
    }

    public static BrokerPosition of(Account account, Commodity commodity, Quantity quantity) {
        return new BrokerPosition(account, commodity, quantity, null);
    }

    public boolean reportsBasis() {
        return reportedBasis != null;
    }
}
