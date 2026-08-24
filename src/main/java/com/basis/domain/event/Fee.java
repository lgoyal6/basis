package com.basis.domain.event;

import com.basis.domain.Account;
import com.basis.domain.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A standalone charge against the account: an account fee, an ADR fee, margin
 * interest. A commission attached to a trade is not this; it rides on the
 * {@link Buy} or {@link Sell} that incurred it.
 *
 * @param expenseAccount the expense account to charge, named explicitly rather than
 *     assembled from a category string, so an unmappable fee type fails at the parser
 *     rather than inventing an account
 */
public record Fee(
        LocalDate date,
        Account account,
        String externalRef,
        String sourceRow,
        Account expenseAccount,
        Money amount) implements LedgerEvent {

    public Fee {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(externalRef, "externalRef");
        Objects.requireNonNull(sourceRow, "sourceRow");
        Objects.requireNonNull(expenseAccount, "expenseAccount");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("fee amount must be positive, was " + amount);
        }
    }
}
