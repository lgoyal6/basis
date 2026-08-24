package com.basis.domain;

/**
 * What kind of thing a commodity is. This is not decoration: it decides whether
 * average cost basis is legal for the commodity, and whether a posting is cash.
 */
public enum CommodityClass {
    /** A currency. Postings in a currency are cash and carry no cost. */
    CURRENCY(false),
    EQUITY(false),
    ETF(false),
    OPTION(false),
    /** US average cost basis is permitted for mutual fund shares. */
    MUTUAL_FUND(true),
    OTHER(false);

    private final boolean averageCostEligible;

    CommodityClass(boolean averageCostEligible) {
        this.averageCostEligible = averageCostEligible;
    }

    /**
     * Whether US rules permit average cost basis for this class of commodity.
     * Ordinary equities and ETFs are never eligible, whatever the broker offers.
     */
    public boolean isAverageCostEligible() {
        return averageCostEligible;
    }

    public boolean isCash() {
        return this == CURRENCY;
    }
}
