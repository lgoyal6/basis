package com.basis.domain.event;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A cash distribution. Declared in week 1, handled in week 2.
 *
 * <p>Gross and withheld are carried separately rather than netted, because the broker
 * reports both and the difference is exactly the sort of thing a reconciliation needs
 * to be able to point at.
 */
public record CashDividend(
        LocalDate date,
        Account account,
        String externalRef,
        String sourceRow,
        Commodity commodity,
        Money grossAmount,
        Money withheldAmount) implements LedgerEvent {

    public CashDividend {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(externalRef, "externalRef");
        Objects.requireNonNull(sourceRow, "sourceRow");
        Objects.requireNonNull(commodity, "commodity");
        Objects.requireNonNull(grossAmount, "grossAmount");
        Objects.requireNonNull(withheldAmount, "withheldAmount");
    }
}
