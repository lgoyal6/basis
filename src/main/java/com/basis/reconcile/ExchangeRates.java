package com.basis.reconcile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Optional;

/**
 * Where an exchange rate comes from, when one is needed to compare two numbers.
 *
 * <p>The same shape as {@link SplitCalendar}, for the same reason: reconciliation needs an
 * outside fact, the fact might not be available, and "not available" has to be a first class
 * answer rather than a zero or a guess. An empty result means no comparison can honestly be
 * made, and the break says that instead of inventing a difference.
 *
 * <p>Deliberately never used to restate the ledger. A lot keeps its cost in the currency it
 * was bought in, forever, because that is what happened. Rates here are used to compare
 * against what a broker reported and to say which rate was used, so a reader can check it.
 * Converting the ledger itself would bake today's rate into a historical fact.
 */
public interface ExchangeRates {

    /** Knows nothing, which is what reconciliation uses when no rates have been fetched. */
    ExchangeRates NONE = (from, to, on) -> Optional.empty();

    /**
     * How many units of {@code to} one unit of {@code from} bought on that date.
     *
     * <p>Implementations may answer with a nearby earlier date when markets were closed, and
     * should say so through {@link Quote#asOf()} rather than pretending the rate is from the
     * date requested.
     */
    Optional<Quote> rate(Currency from, Currency to, LocalDate on);

    /**
     * A rate and the date it actually came from.
     *
     * @param rate units of the target currency per unit of the source currency
     * @param asOf the date this rate is from, which may be earlier than the date asked for
     *     because currency markets close
     * @param source where it came from, so a number on a screen can be traced
     */
    record Quote(BigDecimal rate, LocalDate asOf, String source) {

        public Quote {
            if (rate == null || rate.signum() <= 0) {
                throw new IllegalArgumentException("an exchange rate must be positive, was " + rate);
            }
        }

        /** True when the rate is from a different day than the one asked about. */
        public boolean isStaleFor(LocalDate requested) {
            return !asOf.equals(requested);
        }
    }
}
