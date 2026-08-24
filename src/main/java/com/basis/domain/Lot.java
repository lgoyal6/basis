package com.basis.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * An acquisition lot: a parcel of a commodity bought on one date at one unit cost,
 * plus how much of it is still open.
 *
 * <p>Immutable. {@link #consume(Quantity)} returns a new {@code Lot}; nothing mutates.
 * The authoritative copy lives in the derived {@code lot} table, which is rebuilt from
 * {@code posting} on demand, so this record is a snapshot and never a source of truth.
 *
 * <p>Half of invariant 2 (lot conservation) is enforced right here in the constructor:
 * remaining is never negative and never exceeds the original quantity.
 */
public record Lot(
        LotId id,
        Account account,
        Commodity commodity,
        LocalDate acquisitionDate,
        Price unitCost,
        Quantity originalQuantity,
        Quantity remainingQuantity) {

    public Lot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(commodity, "commodity");
        Objects.requireNonNull(acquisitionDate, "acquisitionDate");
        Objects.requireNonNull(unitCost, "unitCost");
        Objects.requireNonNull(originalQuantity, "originalQuantity");
        Objects.requireNonNull(remainingQuantity, "remainingQuantity");
        if (commodity.isCash()) {
            throw new IllegalArgumentException("cash is not held in lots: " + commodity);
        }
        if (!originalQuantity.isPositive()) {
            throw new IllegalArgumentException("lot " + id + " original quantity must be positive, was " + originalQuantity);
        }
        if (remainingQuantity.isNegative()) {
            throw new IllegalArgumentException("lot " + id + " remaining quantity is negative: " + remainingQuantity);
        }
        if (remainingQuantity.compareTo(originalQuantity) > 0) {
            throw new IllegalArgumentException("lot " + id + " remaining " + remainingQuantity
                    + " exceeds acquired " + originalQuantity);
        }
    }

    /** A freshly opened lot, nothing disposed yet. */
    public static Lot opened(
            LotId id, Account account, Commodity commodity, LocalDate acquisitionDate, Price unitCost, Quantity quantity) {
        return new Lot(id, account, commodity, acquisitionDate, unitCost, quantity, quantity);
    }

    /** The quantity disposed so far. Invariant 2 read directly off the record. */
    public Quantity disposedQuantity() {
        return originalQuantity.minus(remainingQuantity);
    }

    public boolean isOpen() {
        return remainingQuantity.isPositive();
    }

    /** Disposes {@code quantity} from this lot. Throws rather than going short. */
    public Lot consume(Quantity quantity) {
        if (!quantity.isPositive()) {
            throw new IllegalArgumentException("consumed quantity must be positive, was " + quantity);
        }
        if (quantity.compareTo(remainingQuantity) > 0) {
            throw new IllegalArgumentException(
                    "cannot consume " + quantity + " from lot " + id + " with " + remainingQuantity + " remaining");
        }
        return new Lot(id, account, commodity, acquisitionDate, unitCost, originalQuantity,
                remainingQuantity.minus(quantity));
    }

    /** The cost annotation that pins a posting to this lot. */
    public Cost cost() {
        return new Cost(id, unitCost, acquisitionDate);
    }

    /** Basis still held in this lot, rounded once to minor units the same way a posting weight is. */
    public Money remainingBasis() {
        return Money.round(remainingQuantity.multiplyBy(unitCost), unitCost.currency());
    }

    @Override
    public String toString() {
        return remainingQuantity + "/" + originalQuantity + " " + commodity + " " + cost();
    }
}
