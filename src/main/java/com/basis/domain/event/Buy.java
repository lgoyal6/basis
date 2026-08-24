package com.basis.domain.event;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Money;
import com.basis.domain.Price;
import com.basis.domain.Quantity;
import java.time.LocalDate;
import java.util.Objects;

/**
 * An acquisition. Opens exactly one lot.
 *
 * <p>The lot's identifier is not carried here. The handler derives it from this event's
 * idempotency key, so replaying an import produces byte identical lot ids rather than
 * fresh random ones, which is what lets the derived state hash in invariant 7 be stable.
 *
 * <p>{@code commission} is expensed rather than added to the lot's unit cost. See
 * docs/ARCHITECTURE.md section 3 for why, and for the tax treatment it diverges from.
 */
public record Buy(
        LocalDate date,
        Account account,
        String externalRef,
        String sourceRow,
        Commodity commodity,
        Quantity quantity,
        Price price,
        Money commission) implements LedgerEvent {

    public Buy {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(externalRef, "externalRef");
        Objects.requireNonNull(sourceRow, "sourceRow");
        Objects.requireNonNull(commodity, "commodity");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(commission, "commission");
        if (commodity.isCash()) {
            throw new IllegalArgumentException("cannot buy a currency as a commodity: " + commodity);
        }
        if (!quantity.isPositive()) {
            throw new IllegalArgumentException("buy quantity must be positive, was " + quantity);
        }
        if (price.isNegative()) {
            throw new IllegalArgumentException("buy price must not be negative, was " + price);
        }
        if (commission.isNegative()) {
            throw new IllegalArgumentException("commission must not be negative, was " + commission);
        }
        if (!commission.currency().equals(price.currency())) {
            throw new IllegalArgumentException("commission currency " + commission.currency().getCurrencyCode()
                    + " does not match price currency " + price.currency().getCurrencyCode());
        }
    }
}
