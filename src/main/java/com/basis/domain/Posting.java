package com.basis.domain;

import java.util.Currency;
import java.util.Objects;

/**
 * One leg of a transaction: an account, a commodity, a signed quantity, and for a
 * security leg the cost that pins it to an acquisition lot.
 *
 * <p>Cash is not special-cased. A cash leg is a quantity of a currency commodity, so
 * the balance rule is one rule over one list rather than two cases that can drift.
 * See docs/ARCHITECTURE.md section 1.
 */
public record Posting(Account account, Commodity commodity, Quantity quantity, Cost cost) {

    public Posting {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(commodity, "commodity");
        Objects.requireNonNull(quantity, "quantity");
        if (commodity.isCash() && cost != null) {
            throw new IllegalArgumentException("cash posting must not carry a cost: " + account + " " + commodity);
        }
        if (!commodity.isCash() && cost == null) {
            throw new IllegalArgumentException(
                    "security posting must carry a cost pinning it to a lot: " + account + " " + commodity);
        }
    }

    /** A cash leg: a signed quantity of a currency. */
    public static Posting cash(Account account, Money amount) {
        return new Posting(account, Commodity.of(amount.currency()), Quantity.of(amount.toMajorUnits()), null);
    }

    /** A security leg, positive to acquire and negative to dispose. */
    public static Posting security(Account account, Commodity commodity, Quantity quantity, Cost cost) {
        return new Posting(account, commodity, quantity, cost);
    }

    public boolean hasCost() {
        return cost != null;
    }

    public boolean isCash() {
        return commodity.isCash();
    }

    /** The currency this posting weighs in. */
    public Currency currency() {
        return hasCost() ? cost.unitCost().currency() : commodity.asCurrency();
    }

    /**
     * The posting's contribution to the balance check, at cost.
     *
     * <p>Rounded here, once, per posting. That is what makes the proceeds identity
     * exact in minor units rather than approximate: total basis is defined as the sum
     * of these weights. See docs/ARCHITECTURE.md section 2.
     */
    public Money weight() {
        if (hasCost()) {
            return Money.round(quantity.multiplyBy(cost.unitCost()), cost.unitCost().currency());
        }
        // A cash leg weighs exactly its own amount. Inexact cash is rejected, not rounded.
        return Money.of(quantity.value(), commodity.asCurrency());
    }

    @Override
    public String toString() {
        return account + "  " + quantity + " " + commodity + (hasCost() ? " " + cost : "");
    }
}
