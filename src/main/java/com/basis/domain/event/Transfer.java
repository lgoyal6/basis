package com.basis.domain.event;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Quantity;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Movement of cash or securities between accounts, including in and out of the
 * system. Declared in week 1, handled in week 2.
 *
 * <p>A securities transfer has to carry its lots across, or the receiving account
 * loses its acquisition dates and every later disposal reports the wrong holding
 * period. That is why this is not a week 1 event despite looking like the simplest
 * one in the list.
 */
public record Transfer(
        LocalDate date,
        Account fromAccount,
        Account toAccount,
        String externalRef,
        String sourceRow,
        Commodity commodity,
        Quantity quantity) implements LedgerEvent {

    public Transfer {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(fromAccount, "fromAccount");
        Objects.requireNonNull(toAccount, "toAccount");
        Objects.requireNonNull(externalRef, "externalRef");
        Objects.requireNonNull(sourceRow, "sourceRow");
        Objects.requireNonNull(commodity, "commodity");
        Objects.requireNonNull(quantity, "quantity");
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
