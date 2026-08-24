package com.basis.reconcile;

import com.basis.domain.Commodity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A split the reference data knows about, and when that data was last confirmed.
 *
 * <p>{@code fetchedAt} rides along because week 0 established that the free market data
 * tier caps how many symbols can be refreshed per day. A break explained by reference data
 * that is six months stale is a weaker claim than one explained by data from this morning,
 * and the person reading it should be able to tell which they have.
 */
public record KnownSplit(Commodity commodity, LocalDate date, long numerator, long denominator, Instant fetchedAt) {

    public KnownSplit {
        Objects.requireNonNull(commodity, "commodity");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(fetchedAt, "fetchedAt");
        if (numerator <= 0 || denominator <= 0) {
            throw new IllegalArgumentException("split ratio must be positive, was " + numerator + ":" + denominator);
        }
    }

    /** True when this split's ratio is the one the arithmetic suggested. */
    public boolean matches(Ratio ratio) {
        return Math.multiplyExact(numerator, ratio.denominator())
                == Math.multiplyExact(denominator, ratio.numerator());
    }

    public Ratio ratio() {
        return new Ratio(numerator, denominator);
    }
}
