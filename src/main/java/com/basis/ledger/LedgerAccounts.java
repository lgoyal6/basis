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

    /**
     * Where the unavoidable sub cent residue of a corporate action lands.
     *
     * <p>A split restates a lot's unit cost, and a unit cost has six decimal places. Over
     * a large enough share count no scale 6 unit cost reproduces the lot's basis to the
     * cent, so something has to absorb the difference. Booking it to equity keeps it
     * inside the ledger, visible, and queryable, rather than letting a position's basis
     * drift by an amount nobody can account for. See docs/ARCHITECTURE.md section 19.
     */
    public static final Account ROUNDING = Account.of("Equity:Rounding:CorporateActions");

    /**
     * Tax withheld at source on a distribution.
     *
     * <p>Booked as an expense rather than as a prepaid tax asset. basis is not a tax
     * product, so it has nowhere to eventually apply a credit from, and calling withheld
     * tax an asset would imply a recoverability this ledger cannot assess.
     */
    public static final Account WITHHOLDING_TAX = Account.of("Expenses:Taxes:Withholding");

    /**
     * Interest income.
     *
     * <p>Deliberately not filed under dividends. They are different kinds of income, taxed
     * differently, and a question about dividend income should not find interest sitting in
     * its total.
     */
    public static final Account INTEREST_INCOME = Account.of("Income:Interest");

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

    /**
     * Dividend income, per commodity.
     *
     * <p>Per commodity rather than one bucket, because "you received 340.00 in dividends"
     * is not something a reconciliation can argue with a broker about, and
     * "you received 12.00 from KO on this date" is.
     */
    public static Account dividendIncome(Commodity commodity) {
        return Account.of("Income:Dividends").child(commodity.symbol());
    }

    /**
     * True for an account that holds an actual cash balance, as opposed to a contra
     * account that happens to be denominated in a currency.
     * {@code Assets:Broker:IBKR:Cash} yes, {@code Income:Dividends:AAPL} no.
     */
    public static boolean isCashBalance(Account account) {
        return account.leaf().equals(CASH_LEAF);
    }

    /** True for the accounts a realized gain is booked to. */
    public static boolean isRealizedGain(Account account) {
        return account.isUnder(Account.of("Income:CapitalGains"));
    }
}
