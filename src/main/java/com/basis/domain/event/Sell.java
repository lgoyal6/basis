package com.basis.domain.event;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.LotSelectionMethod;
import com.basis.domain.Money;
import com.basis.domain.Price;
import com.basis.domain.Quantity;
import com.basis.domain.SpecificLotRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A disposal. Consumes one or more open lots and realizes a gain or a loss.
 *
 * <p>{@code quantity} is stated positive; the handler emits the negative postings. The
 * realized gain is never carried here and never computed: it falls out of the balance
 * requirement as the plug posting on {@code Income:CapitalGains:Realized}.
 *
 * @param specificLots the lots this disposal names, non empty only when
 *     {@code method} is {@link LotSelectionMethod#SPECIFIC_LOT}
 */
public record Sell(
        LocalDate date,
        Account account,
        String externalRef,
        String sourceRow,
        Commodity commodity,
        Quantity quantity,
        Price price,
        Money commission,
        LotSelectionMethod method,
        List<SpecificLotRequest> specificLots) implements LedgerEvent {

    public Sell {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(externalRef, "externalRef");
        Objects.requireNonNull(sourceRow, "sourceRow");
        Objects.requireNonNull(commodity, "commodity");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(commission, "commission");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(specificLots, "specificLots");
        specificLots = List.copyOf(specificLots);
        if (commodity.isCash()) {
            throw new IllegalArgumentException("cannot sell a currency as a commodity: " + commodity);
        }
        if (!quantity.isPositive()) {
            throw new IllegalArgumentException("sell quantity must be stated positive, was " + quantity);
        }
        if (price.isNegative()) {
            throw new IllegalArgumentException("sell price must not be negative, was " + price);
        }
        if (commission.isNegative()) {
            throw new IllegalArgumentException("commission must not be negative, was " + commission);
        }
        if (!commission.currency().equals(price.currency())) {
            throw new IllegalArgumentException("commission currency " + commission.currency().getCurrencyCode()
                    + " does not match price currency " + price.currency().getCurrencyCode());
        }
        boolean namesLots = !specificLots.isEmpty();
        boolean shouldNameLots = method == LotSelectionMethod.SPECIFIC_LOT;
        if (namesLots != shouldNameLots) {
            throw new IllegalArgumentException(
                    "specific lots must be named for SPECIFIC_LOT and only for SPECIFIC_LOT, method was " + method
                            + " with " + specificLots.size() + " lots named");
        }
    }

    /** Gross consideration before commission, rounded once. */
    public Money grossProceeds() {
        return Money.round(quantity.multiplyBy(price), price.currency());
    }
}
