package com.basis.importer;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.CommodityClass;
import com.basis.domain.LotSelectionMethod;
import com.basis.domain.Money;
import com.basis.domain.Price;
import com.basis.domain.Quantity;
import com.basis.domain.event.AverageCostElection;
import com.basis.domain.event.OpeningBalance;
import com.basis.domain.event.ReverseSplit;
import com.basis.domain.event.Sell;
import com.basis.domain.event.SpinOff;
import com.basis.domain.event.Split;
import com.basis.domain.event.StockDividend;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;

/**
 * Builds the events a person asserts rather than a broker reports.
 *
 * <p>Corporate actions and opening balances are the ones the ledger has always been able to
 * handle and nothing could reach: they do not appear on a transaction statement in any form
 * a parser should be trusted with, and an opening balance by definition predates every
 * statement anyone has.
 *
 * <p>Each event's reference is derived from the command's own content, so running the same
 * command twice is a no op rather than a duplicate. That is the same idempotency the
 * statement importer relies on, reached a different way: a statement row identifies itself
 * by its position in a file, and an assertion identifies itself by what it says.
 */
public final class AssertedEntries {

    private static final Currency USD = Currency.getInstance("USD");

    private AssertedEntries() {
    }

    public static Split split(Account account, Commodity commodity, long numerator, long denominator,
            LocalDate on) {
        String reference = reference("split", commodity.symbol(), on, numerator + ":" + denominator);
        return new Split(on, account, reference, sourceRow(reference, account), commodity,
                numerator, denominator);
    }

    public static ReverseSplit reverseSplit(Account account, Commodity commodity, long numerator,
            long denominator, LocalDate on) {
        String reference = reference("reverse-split", commodity.symbol(), on, numerator + ":" + denominator);
        return new ReverseSplit(on, account, reference, sourceRow(reference, account), commodity,
                numerator, denominator);
    }

    public static StockDividend stockDividend(Account account, Commodity commodity, Quantity shares,
            LocalDate on) {
        String reference = reference("stock-dividend", commodity.symbol(), on, shares.toString());
        return new StockDividend(on, account, reference, sourceRow(reference, account), commodity, shares);
    }

    public static SpinOff spinOff(Account account, Commodity parent, Commodity spunOff,
            Quantity perParentShare, BigDecimal basisFraction, LocalDate on) {
        String reference = reference("spin-off", parent.symbol() + ">" + spunOff.symbol(), on,
                perParentShare + "@" + basisFraction.toPlainString());
        return new SpinOff(on, account, reference, sourceRow(reference, account), parent, spunOff,
                perParentShare, basisFraction);
    }

    /** An opening balance of a security, carrying the cost basis it was acquired at. */
    public static OpeningBalance openingSecurity(Account account, Commodity commodity, Quantity quantity,
            Price unitCost, LocalDate on) {
        String reference = reference("open", commodity.symbol(), on, quantity + "@" + unitCost);
        return new OpeningBalance(on, account, reference, sourceRow(reference, account), commodity,
                quantity, unitCost);
    }

    /** An opening balance of cash. */
    public static OpeningBalance openingCash(Account account, Money amount, LocalDate on) {
        String reference = reference("open", amount.currency().getCurrencyCode(), on, amount.toString());
        return new OpeningBalance(on, account, reference, sourceRow(reference, account),
                Commodity.of(amount.currency()), Quantity.of(amount.toMajorUnits()), null);
    }

    /**
     * The sale of a fractional share that a reverse split left behind.
     *
     * <p>Cash in lieu is a real disposal and is frequently the one taxable event in a
     * corporate action that a statement does not label as one. It is a separate event from
     * the reverse split rather than a field on it, because that is what actually happened:
     * the split restated the position, and then the broker sold the fraction nobody can
     * hold. Two events, in that order, each meaning one thing.
     */
    public static Sell cashInLieu(Account account, Commodity commodity, Quantity fraction,
            Money proceeds, LocalDate on) {
        String reference = reference("cash-in-lieu", commodity.symbol(), on, fraction + "=" + proceeds);
        Price price = Price.of(
                proceeds.toMajorUnits().divide(fraction.value(), Price.SCALE, java.math.RoundingMode.HALF_EVEN),
                proceeds.currency());
        return new Sell(on, account, reference, sourceRow(reference, account), commodity,
                fraction, price, Money.zero(proceeds.currency()), LotSelectionMethod.FIFO, List.of());
    }

    /** Elects average cost for a holding, restating it to one pooled cost per share. */
    public static AverageCostElection averageCost(Account account, Commodity commodity, LocalDate on) {
        String reference = reference("average-cost", commodity.symbol(), on, "election");
        return new AverageCostElection(on, account, reference, sourceRow(reference, account), commodity);
    }

    /** Reads a commodity from the command line, defaulting to an ordinary equity. */
    public static Commodity commodity(String symbol, String kind) {
        CommodityClass commodityClass = kind == null || kind.isBlank()
                ? CommodityClass.EQUITY
                : CommodityClass.valueOf(kind.trim().toUpperCase(java.util.Locale.ROOT));
        return commodityClass == CommodityClass.CURRENCY
                ? Commodity.of(Currency.getInstance(symbol.toUpperCase(java.util.Locale.ROOT)))
                : new Commodity(symbol.toUpperCase(java.util.Locale.ROOT), commodityClass);
    }

    public static Money usd(BigDecimal amount) {
        return Money.of(amount, USD);
    }

    /**
     * Derived from what the command says, so the same command run twice is one event.
     *
     * <p>Combined with the source row in the idempotency key, this is what makes applying a
     * split safe to retry: a nervous second run corrects nothing and duplicates nothing.
     */
    private static String reference(String command, String subject, LocalDate on, String detail) {
        return "asserted:" + command + ":" + subject + ":" + on + ":" + detail;
    }

    /** What someone typed, which for an assertion is the equivalent of the statement line. */
    private static String sourceRow(String reference, Account account) {
        return "{\"source\":\"asserted\",\"account\":" + quote(account.name())
                + ",\"command\":" + quote(reference) + "}";
    }

    private static String quote(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Reads a ratio written as {@code 4:1}. */
    public static long[] ratio(String text) {
        String[] parts = text.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("a ratio is written as new:old, for example 4:1, but got '"
                    + text + "'");
        }
        try {
            return new long[] {Long.parseLong(parts[0].trim()), Long.parseLong(parts[1].trim())};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("a ratio needs two whole numbers, but got '" + text + "'");
        }
    }
}
