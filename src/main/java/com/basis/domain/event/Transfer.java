package com.basis.domain.event;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.LotSelectionMethod;
import com.basis.domain.Quantity;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Movement of cash or securities between accounts, including in and out of the system.
 *
 * <p>A securities transfer has to carry its lots across, or the receiving account
 * loses its acquisition dates and every later disposal reports the wrong holding
 * period. That is why this is not a week 1 event despite looking like the simplest
 * one in the list.
 *
 * @param method which lots a partial securities transfer moves. Stated on the event
 *     rather than defaulted inside the handler, because which lots leave an account
 *     changes every later gain computed in both accounts, and a choice that matters
 *     that much belongs in the record that gets replayed. Ignored for cash.
 */
public record Transfer(
        LocalDate date,
        Account fromAccount,
        Account toAccount,
        String externalRef,
        String sourceRow,
        Commodity commodity,
        Quantity quantity,
        LotSelectionMethod method) implements LedgerEvent {

    public Transfer {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(fromAccount, "fromAccount");
        Objects.requireNonNull(toAccount, "toAccount");
        Objects.requireNonNull(externalRef, "externalRef");
        Objects.requireNonNull(sourceRow, "sourceRow");
        Objects.requireNonNull(commodity, "commodity");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(method, "method");
        if (method == LotSelectionMethod.SPECIFIC_LOT) {
            throw new IllegalArgumentException("a transfer cannot name specific lots: there is nowhere on this"
                    + " event to name them. Use FIFO, LIFO or HIFO.");
        }
        if (!quantity.isPositive()) {
            throw new IllegalArgumentException("transfer quantity must be positive, was " + quantity);
        }
        if (fromAccount.equals(toAccount)) {
            throw new IllegalArgumentException("transfer source and destination are the same: " + fromAccount);
        }
    }

    /** The receiving account. {@link #fromAccount()} is the other side. */
    @Override
    public Account account() {
        return toAccount;
    }
}
