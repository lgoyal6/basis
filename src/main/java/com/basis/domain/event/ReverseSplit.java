package com.basis.domain.event;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A reverse stock split: {@code denominator} shares become {@code numerator}.
 * See {@link Split} for why this is its own event and not a ratio below one.
 *
 * <p>Reverse splits are where fractional share handling stops being academic: the
 * broker usually pays cash for the remainder, which is a taxable disposal the
 * statement may not label as one.
 */
public record ReverseSplit(
        LocalDate date,
        Account account,
        String externalRef,
        String sourceRow,
        Commodity commodity,
        long numerator,
        long denominator) implements LedgerEvent {

    public ReverseSplit {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(externalRef, "externalRef");
        Objects.requireNonNull(sourceRow, "sourceRow");
        Objects.requireNonNull(commodity, "commodity");
        if (numerator <= 0 || denominator <= 0) {
            throw new IllegalArgumentException(
                    "reverse split ratio must be positive, was " + numerator + ":" + denominator);
        }
        if (numerator >= denominator) {
            throw new IllegalArgumentException("a reverse split must reduce the share count, but "
                    + numerator + ":" + denominator + " does not. Use Split.");
        }
    }
}
