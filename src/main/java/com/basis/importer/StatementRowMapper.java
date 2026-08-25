package com.basis.importer;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.CommodityClass;
import com.basis.domain.LotSelectionMethod;
import com.basis.domain.Money;
import com.basis.domain.Price;
import com.basis.domain.Quantity;
import com.basis.domain.event.Buy;
import com.basis.domain.event.CashDividend;
import com.basis.domain.event.Fee;
import com.basis.domain.event.LedgerEvent;
import com.basis.domain.event.Sell;
import com.basis.domain.event.Transfer;
import com.basis.importer.FidelityActions.Kind;
import com.basis.ledger.LedgerAccounts;
import com.basis.reference.SymbolMapping;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

/**
 * Turns a statement row into the events it represents.
 *
 * <p>Usually one event per row, sometimes two: a reinvested dividend is a distribution and
 * then a purchase, and booking it as a single free acquisition would put cash income
 * nowhere and give the new shares no basis.
 *
 * <p>Ticker renames are applied here rather than left to reconciliation. A statement from
 * 2021 says FB and today's holding is META, and resolving that at import means the ledger
 * has one position rather than two that a reconciliation later has to reunite.
 *
 * <p>Nothing here guesses. A row whose action is not recognised, or whose numbers do not
 * support the event it claims to be, stops the import and names the line. The alternative
 * is a silently dropped transaction, which surfaces weeks later as a break with a
 * confidently wrong explanation attached, and that is the one failure this project exists
 * to avoid.
 */
public final class StatementRowMapper {

    private static final Currency USD = Currency.getInstance("USD");

    private final Account brokerAccount;
    private final Account externalAccount;
    private final SymbolMapping renames;
    private final String source;

    /**
     * @param externalAccount where cash moving in and out comes from and goes to, so a
     *     deposit is a transfer rather than money appearing from nowhere
     */
    public StatementRowMapper(
            Account brokerAccount, Account externalAccount, SymbolMapping renames, String source) {
        this.brokerAccount = brokerAccount;
        this.externalAccount = externalAccount;
        this.renames = renames;
        this.source = source;
    }

    /** @throws StatementFormatException if the row cannot be understood */
    public List<LedgerEvent> toEvents(StatementRow row) {
        Kind kind = FidelityActions.classify(row.action())
                .orElseThrow(() -> new StatementFormatException(row.location(source)
                        + ": the action '" + row.action() + "' is not recognised."
                        + " Nothing has been imported. Add the phrase to FidelityActions if it is a"
                        + " transaction basis should understand. Known prefixes: "
                        + String.join(", ", FidelityActions.known())));

        return switch (kind) {
            case BUY -> List.of(buy(row));
            case SELL -> List.of(sell(row));
            case CASH_DIVIDEND -> List.of(dividend(row));
            case REINVESTMENT -> reinvestment(row);
            case FEE -> List.of(fee(row, LedgerAccounts.COMMISSIONS));
            case WITHHOLDING -> List.of(fee(row, LedgerAccounts.WITHHOLDING_TAX));
            case CASH_TRANSFER -> List.of(cashTransfer(row));
            case SECURITY_TRANSFER -> List.of(securityTransfer(row));
        };
    }

    private LedgerEvent buy(StatementRow row) {
        return new Buy(row.date(), brokerAccount, reference(row), row.asJson(source),
                commodity(row), positiveQuantity(row), price(row), charges(row));
    }

    private LedgerEvent sell(StatementRow row) {
        // Fidelity writes a sale's quantity negative. The event states it positive and the
        // handler emits the negative postings, so the sign is normalised here rather than
        // leaving two conventions loose in the ledger.
        return new Sell(row.date(), brokerAccount, reference(row), row.asJson(source),
                commodity(row), positiveQuantity(row), price(row), charges(row),
                LotSelectionMethod.FIFO, List.of());
    }

    private LedgerEvent dividend(StatementRow row) {
        Money gross = money(row.amountOrZero().abs(), row);
        return new CashDividend(row.date(), brokerAccount, reference(row), row.asJson(source),
                commodity(row), gross, Money.zero(USD));
    }

    /**
     * A reinvested dividend is two events: the cash arrives, then it buys shares.
     *
     * <p>The distribution's size is taken from the shares bought rather than from the
     * Amount column, because Fidelity reports the reinvestment row's amount as the cash
     * leaving to buy the shares, which is the same number with the opposite sign.
     */
    private List<LedgerEvent> reinvestment(StatementRow row) {
        Quantity shares = positiveQuantity(row);
        Price unitPrice = price(row);
        Money cost = Money.round(shares.multiplyBy(unitPrice), USD);
        if (!cost.isPositive()) {
            throw new StatementFormatException(row.location(source)
                    + ": a reinvestment has to buy something, but quantity times price is " + cost);
        }

        List<LedgerEvent> events = new ArrayList<>();
        events.add(new CashDividend(row.date(), brokerAccount, reference(row) + ":cash", row.asJson(source),
                commodity(row), cost, Money.zero(USD)));
        events.add(new Buy(row.date(), brokerAccount, reference(row) + ":buy", row.asJson(source),
                commodity(row), shares, unitPrice, Money.zero(USD)));
        return List.copyOf(events);
    }

    private LedgerEvent fee(StatementRow row, Account expenseAccount) {
        Money amount = money(row.amountOrZero().abs(), row);
        if (!amount.isPositive()) {
            throw new StatementFormatException(row.location(source)
                    + ": a charge of " + amount + " is not a charge");
        }
        return new Fee(row.date(), brokerAccount, reference(row), row.asJson(source), expenseAccount, amount);
    }

    /** Direction comes from the sign of the amount: positive is money arriving. */
    private LedgerEvent cashTransfer(StatementRow row) {
        BigDecimal amount = row.amountOrZero();
        if (amount.signum() == 0) {
            throw new StatementFormatException(row.location(source) + ": a transfer of nothing");
        }
        boolean incoming = amount.signum() > 0;
        return new Transfer(row.date(),
                incoming ? externalAccount : brokerAccount,
                incoming ? brokerAccount : externalAccount,
                reference(row), row.asJson(source),
                Commodity.of(USD), Quantity.of(amount.abs()), LotSelectionMethod.FIFO);
    }

    private LedgerEvent securityTransfer(StatementRow row) {
        BigDecimal quantity = row.quantityOrZero();
        if (quantity.signum() == 0) {
            throw new StatementFormatException(row.location(source)
                    + ": a securities transfer with no quantity");
        }
        boolean incoming = quantity.signum() > 0;
        return new Transfer(row.date(),
                incoming ? externalAccount : brokerAccount,
                incoming ? brokerAccount : externalAccount,
                reference(row), row.asJson(source),
                commodity(row), Quantity.of(quantity.abs()), LotSelectionMethod.FIFO);
    }

    private Commodity commodity(StatementRow row) {
        if (!row.hasSymbol()) {
            throw new StatementFormatException(row.location(source)
                    + ": this row needs a symbol and the Symbol column is empty");
        }
        return renames.resolve(new Commodity(row.symbol(), CommodityClass.EQUITY));
    }

    private Quantity positiveQuantity(StatementRow row) {
        BigDecimal quantity = row.quantityOrZero().abs();
        if (quantity.signum() <= 0) {
            throw new StatementFormatException(row.location(source)
                    + ": expected a share quantity and found '" + row.quantity() + "'");
        }
        return Quantity.of(quantity);
    }

    /**
     * Price per share, derived from the amount when the column is blank.
     *
     * <p>Some rows carry an amount and a quantity but no price. Deriving it keeps the lot's
     * cost right; refusing the row would lose a real transaction over a missing cell.
     */
    private Price price(StatementRow row) {
        BigDecimal stated = row.price();
        if (stated != null && stated.abs().signum() > 0) {
            return Price.of(stated.abs(), USD);
        }
        BigDecimal quantity = row.quantityOrZero().abs();
        BigDecimal amount = row.amountOrZero().abs();
        if (quantity.signum() == 0 || amount.signum() == 0) {
            throw new StatementFormatException(row.location(source)
                    + ": no price, and none can be worked out from a quantity of "
                    + row.quantity() + " and an amount of " + row.amount());
        }
        return Price.of(amount.divide(quantity, Price.SCALE, java.math.RoundingMode.HALF_EVEN), USD);
    }

    private Money charges(StatementRow row) {
        return money(row.totalCharges().abs(), row);
    }

    private Money money(BigDecimal amount, StatementRow row) {
        try {
            return Money.of(amount, USD);
        } catch (ArithmeticException e) {
            throw new StatementFormatException(row.location(source)
                    + ": " + amount.toPlainString() + " is not a whole number of cents");
        }
    }

    /**
     * What makes two otherwise identical rows different transactions.
     *
     * <p>Fidelity's export carries no per row identifier, so the row's position in the file
     * is it. Combined with the verbatim line in the idempotency key, that means re-importing
     * the same file is a no op while two genuinely separate fills of the same size at the
     * same price on the same day stay separate.
     */
    private static String reference(StatementRow row) {
        return "row-" + row.ordinal();
    }
}
