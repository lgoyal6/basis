package com.basis.domain.event;

import com.basis.domain.Account;
import com.basis.domain.Money;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Interest credited to the account.
 *
 * <p>A gap in the ledger rather than a quirk of one broker. Income from a security is a
 * {@link CashDividend}, and a charge is a {@link Fee}, and until now there was no way to
 * book income that comes from no security at all: a cash sweep, a bond coupon, a balance
 * paying its own way.
 *
 * <p>{@link CashDividend} cannot stand in for it. It requires a commodity and refuses a
 * currency, which is right: booking interest to {@code Income:Dividends:USD} would file it
 * under a heading it does not belong to, and a tax question about dividend income would find
 * it there.
 *
 * <p>Carries no commodity, because the thing that earned it is a cash balance rather than a
 * holding. Withholding is separate, as it is for a dividend, since the broker reports it on
 * its own line and the difference between what was earned and what arrived is exactly the
 * sort of thing a reconciliation needs to be able to point at.
 */
public record InterestEarned(
        LocalDate date,
        Account account,
        String externalRef,
        String sourceRow,
        Money grossAmount,
        Money withheldAmount) implements LedgerEvent {

    public InterestEarned {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(externalRef, "externalRef");
        Objects.requireNonNull(sourceRow, "sourceRow");
        Objects.requireNonNull(grossAmount, "grossAmount");
        Objects.requireNonNull(withheldAmount, "withheldAmount");
        if (!grossAmount.isPositive()) {
            throw new IllegalArgumentException("interest earned must be positive, was " + grossAmount
                    + ". Interest charged is a fee, not negative income.");
        }
        if (withheldAmount.isNegative()) {
            throw new IllegalArgumentException("withheld amount must not be negative, was " + withheldAmount);
        }
        if (!withheldAmount.currency().equals(grossAmount.currency())) {
            throw new IllegalArgumentException("withheld currency " + withheldAmount.currency().getCurrencyCode()
                    + " does not match gross currency " + grossAmount.currency().getCurrencyCode());
        }
        if (withheldAmount.compareTo(grossAmount) > 0) {
            throw new IllegalArgumentException("withheld " + withheldAmount + " exceeds the interest of "
                    + grossAmount);
        }
    }

    /** What actually reached the account. */
    public Money netAmount() {
        return grossAmount.minus(withheldAmount);
    }
}
