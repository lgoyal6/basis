package com.basis.support;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.LotSelectionMethod;
import com.basis.domain.Money;
import com.basis.domain.Price;
import com.basis.domain.Quantity;
import com.basis.domain.SpecificLotRequest;
import com.basis.domain.event.Buy;
import com.basis.domain.event.CashDividend;
import com.basis.domain.event.Fee;
import com.basis.domain.event.OpeningBalance;
import com.basis.domain.event.Sell;
import com.basis.domain.event.Transfer;
import com.basis.ledger.LedgerAccounts;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

/** Shared test fixtures. Names match the worked examples in the week 1 mandate. */
public final class Fixtures {

    public static final Currency USD = Currency.getInstance("USD");
    public static final Currency EUR = Currency.getInstance("EUR");

    public static final Account IBKR = Account.of("Assets:Broker:IBKR");
    public static final Account IBKR_CASH = LedgerAccounts.cash(IBKR);

    /** A second broker, so transfers have somewhere to go and positions stay separable. */
    public static final Account SCHWAB = Account.of("Assets:Broker:Schwab");
    public static final Account SCHWAB_CASH = LedgerAccounts.cash(SCHWAB);

    /** Outside the brokerage, so a deposit is just a transfer from somewhere else. */
    public static final Account BANK = Account.of("Assets:Bank:Chase");

    public static final Commodity AAPL = Commodity.equity("AAPL");
    public static final Commodity MSFT = Commodity.equity("MSFT");
    public static final Commodity VTSAX = Commodity.mutualFund("VTSAX");
    public static final Commodity SPY = Commodity.etf("SPY");

    public static final LocalDate JAN_15 = LocalDate.of(2026, 1, 15);
    public static final LocalDate FEB_01 = LocalDate.of(2026, 2, 1);

    private Fixtures() {
    }

    public static Money usd(String major) {
        return Money.of(new BigDecimal(major), USD);
    }

    public static Price price(String major) {
        return Price.of(major, USD);
    }

    public static Quantity qty(String value) {
        return Quantity.of(value);
    }

    /** A source row that is valid JSON, since txn.source_row is JSONB. */
    public static String sourceRow(String ref) {
        return "{\"ref\":\"" + ref + "\"}";
    }

    public static Buy buy(LocalDate date, String ref, Commodity commodity, String quantity,
            String unitPrice, String commission) {
        return new Buy(date, IBKR, ref, sourceRow(ref), commodity, qty(quantity),
                price(unitPrice), usd(commission));
    }

    public static Sell sell(LocalDate date, String ref, Commodity commodity, String quantity,
            String unitPrice, String commission, LotSelectionMethod method) {
        return new Sell(date, IBKR, ref, sourceRow(ref), commodity, qty(quantity),
                price(unitPrice), usd(commission), method, List.of());
    }

    public static Sell sellSpecific(LocalDate date, String ref, Commodity commodity,
            List<SpecificLotRequest> lots, String unitPrice, String commission) {
        Quantity total = Quantity.ZERO;
        for (SpecificLotRequest lot : lots) {
            total = total.plus(lot.quantity());
        }
        return new Sell(date, IBKR, ref, sourceRow(ref), commodity, total,
                price(unitPrice), usd(commission), LotSelectionMethod.SPECIFIC_LOT, lots);
    }

    public static Fee fee(LocalDate date, String ref, String amount) {
        return new Fee(date, IBKR, ref, sourceRow(ref), Account.of("Expenses:Fees:Account"), usd(amount));
    }

    public static OpeningBalance openingCash(LocalDate date, String ref, String amount) {
        return new OpeningBalance(date, IBKR, ref, sourceRow(ref), Commodity.of(USD), qty(amount), null);
    }

    public static CashDividend dividend(LocalDate date, String ref, Commodity commodity,
            String gross, String withheld) {
        return new CashDividend(date, IBKR, ref, sourceRow(ref), commodity, usd(gross), usd(withheld));
    }

    public static Transfer transferCash(LocalDate date, String ref, Account from, Account to, String amount) {
        return new Transfer(date, from, to, ref, sourceRow(ref), Commodity.of(USD), qty(amount),
                LotSelectionMethod.FIFO);
    }

    public static Transfer transferSecurity(LocalDate date, String ref, Account from, Account to,
            Commodity commodity, String quantity, LotSelectionMethod method) {
        return new Transfer(date, from, to, ref, sourceRow(ref), commodity, qty(quantity), method);
    }

    public static OpeningBalance openingSecurity(LocalDate date, String ref, Commodity commodity,
            String quantity, String unitCost) {
        return new OpeningBalance(date, IBKR, ref, sourceRow(ref), commodity, qty(quantity), price(unitCost));
    }
}
