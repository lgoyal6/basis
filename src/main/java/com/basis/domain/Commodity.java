package com.basis.domain;

import java.util.Currency;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A commodity is anything a position can be held in: a security or a currency.
 *
 * <p>The symbol here is the ticker as of the transaction, not a stable identity.
 * Ticker renames are resolved by a hand-maintained mapping file (see
 * docs/FEASIBILITY.md, question 3) and that resolution is week 3 work.
 */
public record Commodity(String symbol, CommodityClass commodityClass) implements Comparable<Commodity> {

    private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9][A-Z0-9.\\-]*");

    public Commodity {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(commodityClass, "commodityClass");
        if (!SYMBOL.matcher(symbol).matches()) {
            throw new IllegalArgumentException("illegal commodity symbol: " + symbol);
        }
    }

    public static Commodity equity(String symbol) {
        return new Commodity(symbol, CommodityClass.EQUITY);
    }

    public static Commodity etf(String symbol) {
        return new Commodity(symbol, CommodityClass.ETF);
    }

    public static Commodity mutualFund(String symbol) {
        return new Commodity(symbol, CommodityClass.MUTUAL_FUND);
    }

    public static Commodity of(Currency currency) {
        return new Commodity(currency.getCurrencyCode(), CommodityClass.CURRENCY);
    }

    public boolean isCash() {
        return commodityClass.isCash();
    }

    /** The currency this commodity is, for cash commodities only. */
    public Currency asCurrency() {
        if (!isCash()) {
            throw new IllegalStateException(symbol + " is not a currency");
        }
        return Currency.getInstance(symbol);
    }

    @Override
    public int compareTo(Commodity other) {
        return symbol.compareTo(other.symbol);
    }

    @Override
    public String toString() {
        return symbol;
    }
}
