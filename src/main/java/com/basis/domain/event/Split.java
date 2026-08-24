package com.basis.domain.event;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A forward stock split, as reported by FMP {@code /splits}: numerator to
 * denominator, so Apple's 2020 split is 4 to 1. Declared in week 1, handled in week 3.
 *
 * <p>{@link ReverseSplit} is a separate event rather than a split with a numerator
 * below its denominator. The arithmetic is the same but the intent is not, and a
 * reconciliation that has to explain a share count to a human is better off saying
 * "reverse split" than "split, 1 to 8".
 */
public record Split(
        LocalDate date,
        Account account,
        String externalRef,
        String sourceRow,
        Commodity commodity,
        long numerator,
        long denominator) implements LedgerEvent {

    public Split {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(externalRef, "externalRef");
        Objects.requireNonNull(sourceRow, "sourceRow");
        Objects.requireNonNull(commodity, "commodity");
        if (numerator <= 0 || denominator <= 0) {
            throw new IllegalArgumentException("split ratio must be positive, was " + numerator + ":" + denominator);
        }
        if (numerator <= denominator) {
            throw new IllegalArgumentException("a forward split must increase the share count, but "
                    + numerator + ":" + denominator + " does not. Use ReverseSplit."
                    + " Refusing this here is what stops a parser silently filing a reverse split as a split"
                    + " and reporting a share count off by the ratio.");
        }
    }
}
