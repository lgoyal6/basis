package com.basis.importer;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.LotSelectionMethod;
import com.basis.domain.Money;
import com.basis.domain.Price;
import com.basis.domain.Quantity;
import com.basis.domain.event.Buy;
import com.basis.domain.event.CashDividend;
import com.basis.domain.event.Fee;
import com.basis.domain.event.InterestEarned;
import com.basis.domain.event.LedgerEvent;
import com.basis.domain.event.Sell;
import com.basis.domain.event.Transfer;
import com.basis.ledger.LedgerAccounts;
import com.basis.reference.CommodityCatalog;
import com.basis.reference.SymbolMapping;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

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

    private final BrokerProfile profile;
    private final Account brokerAccount;
    private final Account externalAccount;
    private final SymbolMapping renames;
    private final CommodityCatalog catalog;
    private final String source;

    /**
     * @param externalAccount where cash moving in and out comes from and goes to, so a
     *     deposit is a transfer rather than money appearing from nowhere
     */
    public StatementRowMapper(
            BrokerProfile profile, Account brokerAccount, Account externalAccount,
            SymbolMapping renames, CommodityCatalog catalog, String source) {
        this.profile = profile;
        this.brokerAccount = brokerAccount;
        this.externalAccount = externalAccount;
        this.renames = renames;
        this.catalog = catalog;
        this.source = source;
    }

    /** @throws StatementFormatException if the row cannot be understood */
    public List<LedgerEvent> toEvents(StatementRow row) {
        ActionKind kind = profile.classify(row.action())
                .orElseThrow(() -> new StatementFormatException(unrecognised(row)));

        return switch (kind) {
            case BUY -> List.of(buy(row));
            case SELL -> List.of(sell(row));
            case CASH_DIVIDEND -> List.of(dividend(row));
            case REINVESTMENT -> reinvestment(row);
            case INTEREST -> List.of(interest(row));
            case FEE -> List.of(fee(row, LedgerAccounts.FEES));
            case WITHHOLDING -> List.of(fee(row, LedgerAccounts.WITHHOLDING_TAX));
            case CASH_TRANSFER -> List.of(cashTransfer(row));
            case SECURITY_TRANSFER -> List.of(securityTransfer(row));
            case IGNORE -> List.of();
        };
    }

    /**
     * Why a row stopped the import, and what to actually do about it.
     *
     * <p>The default advice is to add the phrase to the broker profile, which is right for
     * almost everything and badly wrong for a corporate action. A merger or a split restates
     * a cost basis, and the statement row says only that it happened, never at what ratio or
     * against which lots. Adding the phrase would let the import finish and leave every later
     * sale of that holding computing a gain from a basis nobody established. So when the row
     * looks like one of those, the message says so and names the command that handles it
     * properly. See docs/ARCHITECTURE.md section 29.3.
     */
    private String unrecognised(StatementRow row) {
        String base = row.location(source) + ": the action '" + row.action() + "' is not"
                + " recognised. Nothing has been imported.";
        String corporateAction = corporateActionAdvice(row.action());
        if (corporateAction != null) {
            return base + " " + corporateAction;
        }
        return base + " If this is a transaction basis should understand, add the phrase to the "
                + profile.name() + " profile in config/brokers. Phrases it knows: "
                + String.join(", ", profile.knownPhrases());
    }

    /** Null unless the action reads like a corporate action, which needs a command, not a profile edit. */
    private static String corporateActionAdvice(String action) {
        String text = action.toUpperCase(Locale.ROOT);
        // A rename is the one of these that moves no value, so it does not get the warning
        // about restating a basis. It gets a different and shorter answer.
        if (text.startsWith("NAME CHANGE") || text.startsWith("SYMBOL CHANGE")) {
            return "A rename moves no value and is not an event. Add a line to"
                    + " config/symbol-changes.csv, which is where the ledger keeps renames, then"
                    + " run the import again.";
        }
        for (Map.Entry<String, String> entry : CORPORATE_ACTIONS.entrySet()) {
            if (text.startsWith(entry.getKey())) {
                return "That is a corporate action, and it is left out of the profile on purpose."
                        + " The row records that it happened but not on what terms, so importing it"
                        + " would restate a cost basis nobody stated, and every later sale of that"
                        + " holding would compute its gain from it. " + entry.getValue()
                        + " Then run the import again. Adding the phrase to the profile to get past"
                        + " this is the one edit to that file that will cost you money.";
            }
        }
        return null;
    }

    /**
     * Corporate action wording, mapped to what to do instead.
     *
     * <p>Longest first, so REVERSE SPLIT is not answered as a SPLIT, and each value is a whole
     * sentence because the remedy is not always a command: a rename is a config entry, and a
     * merger needs somebody to read the terms before anything can be applied at all.
     */
    // Package private so a test can hold every command named here against the list the CLI
    // actually dispatches on. Advice that names a command nobody can run is worse than none.
    static final Map<String, String> CORPORATE_ACTIONS = new LinkedHashMap<>();

    static {
        CORPORATE_ACTIONS.put("REVERSE SPLIT", "Apply it with 'basis apply reverse-split', giving the"
                + " ratio from the broker's notice.");
        CORPORATE_ACTIONS.put("CASH IN LIEU", "It belongs to the reverse split that created the"
                + " fraction, and is booked with it: 'basis apply reverse-split <account> <symbol>"
                + " <new:old> --on DATE --cash-in-lieu AMOUNT'. It is a real disposal and usually"
                + " the one taxable event in a corporate action that a statement never labels as"
                + " one.");
        CORPORATE_ACTIONS.put("STOCK DIVIDEND", "Apply it with 'basis apply stock-dividend'.");
        CORPORATE_ACTIONS.put("SPIN OFF", "Apply it with 'basis apply spin-off', giving the basis"
                + " fraction from the company's Form 8937.");
        CORPORATE_ACTIONS.put("SPIN-OFF", "Apply it with 'basis apply spin-off', giving the basis"
                + " fraction from the company's Form 8937.");
        CORPORATE_ACTIONS.put("SPLIT", "Apply it with 'basis apply split', giving the ratio. If the"
                + " symbol has split history available, 'basis refresh-splits' can find it for you.");
        CORPORATE_ACTIONS.put("MERGER", "Read what the merger actually paid out, then apply it as the"
                + " parts it consisted of: a share exchange is 'basis apply split' at the exchange"
                + " ratio, and any cash paid out is a sale. basis will not guess which.");
        CORPORATE_ACTIONS.put("EXCHANGE", "Read the terms, then apply it as what it did: a ratio"
                + " change is 'basis apply split', and a swap into a different security is a sale"
                + " and a purchase.");
        CORPORATE_ACTIONS.put("CONVERSION", "Read the terms, then apply it as what it did: a ratio"
                + " change is 'basis apply split', and a swap into a different security is a sale"
                + " and a purchase.");
    }

    private LedgerEvent buy(StatementRow row) {
        return new Buy(row.date(), brokerAccount, reference(row), row.asJson(source),
                commodity(row), positiveQuantity(row), tradePrice(row, false), charges(row));
    }

    private LedgerEvent sell(StatementRow row) {
        // Exports usually write a sale's quantity negative, and some write it positive with
        // the direction only in the action text. Either way the event states it positive and
        // the handler emits the negative postings, so one sign convention reaches the ledger.
        return new Sell(row.date(), brokerAccount, reference(row), row.asJson(source),
                commodity(row), positiveQuantity(row), tradePrice(row, true), charges(row),
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
     * Amount column, because a reinvestment row's amount is the cash leaving to buy the
     * shares, which is the same number with the opposite sign.
     */
    private List<LedgerEvent> reinvestment(StatementRow row) {
        Quantity shares = positiveQuantity(row);
        Price unitPrice = tradePrice(row, false);
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

    /**
     * Interest credited, which unlike a dividend names no security.
     *
     * <p>The symbol column on one of these rows is usually blank, and where it is not it
     * names the cash sweep the interest was calculated on rather than a holding that paid
     * it. Either way there is nothing to attribute the income to, which is exactly why this
     * is its own event.
     */
    private LedgerEvent interest(StatementRow row) {
        Money gross = money(row.amountOrZero().abs(), row);
        if (!gross.isPositive()) {
            throw new StatementFormatException(row.location(source)
                    + ": interest of " + gross + " is not income. Interest charged is a fee,"
                    + " and belongs on a profile line that maps to FEE.");
        }
        return new InterestEarned(row.date(), brokerAccount, reference(row), row.asJson(source),
                gross, Money.zero(USD));
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
        // The catalog decides what kind of instrument it is, then renames decide what it is
        // called today. A statement from 2021 says FB, and a fund is a fund whichever name
        // it goes by.
        return renames.resolve(catalog.resolve(row.symbol()));
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
     * Price per share for a trade, worked out from the money that actually moved.
     *
     * <p>The Price column is not used when an amount is available, and that is deliberate.
     * A real Fidelity export sold 1.844 shares at a stated price of 271.2 for an amount of
     * 500.00. Multiplying gives 500.0928, so trusting the price column would have credited
     * nine cents that never arrived, and every trade would have left a small cash break
     * behind it. The price is rounded for display; the amount is the money.
     *
     * <p>Charges are added back for a sale and taken off a purchase, because the amount is
     * net of them either way and the unit price is of the shares alone. That keeps the
     * commission expensed rather than capitalised, which is the week 1 decision in
     * docs/ARCHITECTURE.md section 3.
     *
     * @param sale true when the amount is money coming in rather than going out
     */
    private Price tradePrice(StatementRow row, boolean sale) {
        BigDecimal quantity = row.quantityOrZero().abs();
        BigDecimal amount = row.amountOrZero().abs();
        BigDecimal charges = row.totalCharges().abs();

        if (quantity.signum() > 0 && amount.signum() > 0) {
            BigDecimal gross = sale ? amount.add(charges) : amount.subtract(charges);
            if (gross.signum() > 0) {
                return Price.of(gross.divide(quantity, Price.SCALE, java.math.RoundingMode.HALF_EVEN), USD);
            }
        }
        // No amount to work from, so fall back to what the statement said the price was.
        BigDecimal stated = row.price();
        if (stated != null && stated.abs().signum() > 0) {
            return Price.of(stated.abs(), USD);
        }
        throw new StatementFormatException(row.location(source)
                + ": no price, and none can be worked out from a quantity of "
                + row.quantity() + " and an amount of " + row.amount());
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
     * <p>Most exports carry no per row identifier, so the row's position in the file is it.
     * Combined with the verbatim line in the idempotency key, that means re-importing the
     * same file is a no op while two genuinely separate fills of the same size at the same
     * price on the same day stay separate.
     */
    private static String reference(StatementRow row) {
        return "row-" + row.ordinal();
    }
}
