package com.basis.domain.event;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Quantity;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A distribution paid in shares rather than cash. Declared in week 1, handled in
 * week 3 with the other corporate actions, because it has to spread existing basis
 * across a larger share count without creating or destroying value.
 */
public record StockDividend(
        LocalDate date,
        Account account,
        String externalRef,
        String sourceRow,
        Commodity commodity,
        Quantity quantity) implements LedgerEvent {

    public StockDividend {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(externalRef, "externalRef");
        Objects.requireNonNull(sourceRow, "sourceRow");
        Objects.requireNonNull(commodity, "commodity");
        Objects.requireNonNull(quantity, "quantity");
    }
}
