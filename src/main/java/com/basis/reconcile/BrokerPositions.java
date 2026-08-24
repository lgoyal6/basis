package com.basis.reconcile;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Money;
import com.basis.domain.Quantity;
import com.basis.ledger.LedgerAccounts;

/**
 * Builds broker positions using the ledger's account naming convention, so a caller
 * reading a statement does not have to know that a holding lives at
 * {@code <broker>:<symbol>} and cash at {@code <broker>:Cash}.
 */
public final class BrokerPositions {

    private BrokerPositions() {
    }

    public static BrokerPosition held(Account brokerRoot, Commodity commodity, Quantity quantity) {
        return BrokerPosition.of(LedgerAccounts.holding(brokerRoot, commodity), commodity, quantity);
    }

    public static BrokerPosition held(
            Account brokerRoot, Commodity commodity, Quantity quantity, Money reportedBasis) {
        return new BrokerPosition(
                LedgerAccounts.holding(brokerRoot, commodity), commodity, quantity, reportedBasis);
    }

    public static BrokerPosition cash(Account brokerRoot, Money amount) {
        return BrokerPosition.of(LedgerAccounts.cash(brokerRoot), Commodity.of(amount.currency()),
                Quantity.of(amount.toMajorUnits()));
    }
}
