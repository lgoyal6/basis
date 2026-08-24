package com.basis.domain;

/**
 * How a disposal picks which acquisition lots it consumes.
 *
 * <p>This lives in the domain rather than next to the strategies because it is part of
 * what the broker or the taxpayer chose, and therefore part of the imported record.
 * The strategies that implement each method live in {@code com.basis.ledger.lot} and
 * are looked up from this enum, which keeps the domain free of a dependency on them.
 */
public enum LotSelectionMethod {
    FIFO,
    LIFO,
    HIFO,
    /** The disposal names its lots explicitly. */
    SPECIFIC_LOT,
    /**
     * Permitted in the US only for mutual fund shares and certain dividend
     * reinvestment plans, never for ordinary equities. Not implemented in week 1.
     */
    AVERAGE_COST
}
