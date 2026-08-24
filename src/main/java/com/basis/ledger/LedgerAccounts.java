package com.basis.ledger;

import com.basis.domain.Account;
import com.basis.domain.Commodity;

/**
 * The account naming convention. There is no broker entity and no portfolio entity:
 * a broker is a prefix in the account tree, so a second broker adds rows rather than
 * tables.
 */
public final class LedgerAccounts {

    /** Where cash sits under a broker root. */
    public static final String CASH_LEAF = "Cash";

    /** The plug account for a disposal. Realized gain is whatever lands here. */
    public static final Account REALIZED_GAINS = Account.of("Income:CapitalGains:Realized");

    /** The plug account for an opening balance, so the ledger stays closed. */
    public static final Account OPENING_BALANCES = Account.of("Equity:Opening-Balances");

    /** Trade commissions, expensed rather than capitalised. See docs/ARCHITECTURE.md section 3. */
    public static final Account COMMISSIONS = Account.of("Expenses:Commissions");

    private LedgerAccounts() {
    }

    /** The cash account under a broker root, for example {@code Assets:Broker:IBKR:Cash}. */
    public static Account cash(Account brokerRoot) {
        return brokerRoot.child(CASH_LEAF);
    }

    /** The holding account for a commodity, for example {@code Assets:Broker:IBKR:AAPL}. */
    public static Account holding(Account brokerRoot, Commodity commodity) {
        return brokerRoot.child(commodity.symbol());
    }

    /** True for the accounts a realized gain is booked to. */
    public static boolean isRealizedGain(Account account) {
        return account.isUnder(Account.of("Income:CapitalGains"));
    }
}
