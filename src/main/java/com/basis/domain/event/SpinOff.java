package com.basis.domain.event;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.Quantity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A spin off: the parent distributes shares of a new company, and part of the parent's
 * cost basis goes with them.
 *
 * <p>{@code parentBasisFraction} is the share of the parent's cost basis that moves to
 * the spun off entity, which the issuer publishes and no price feed can derive. This
 * is the event most likely to raise a break and ask rather than guess.
 */
public record SpinOff(
        LocalDate date,
        Account account,
        String externalRef,
        String sourceRow,
        Commodity parent,
        Commodity spunOff,
        Quantity quantityPerParentShare,
        BigDecimal parentBasisFraction) implements LedgerEvent {

    public SpinOff {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(externalRef, "externalRef");
        Objects.requireNonNull(sourceRow, "sourceRow");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(spunOff, "spunOff");
        Objects.requireNonNull(quantityPerParentShare, "quantityPerParentShare");
        Objects.requireNonNull(parentBasisFraction, "parentBasisFraction");
        if (parentBasisFraction.signum() < 0 || parentBasisFraction.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "parent basis fraction must be between 0 and 1, was " + parentBasisFraction.toPlainString());
        }
        if (parent.equals(spunOff)) {
            throw new IllegalArgumentException("a company cannot spin off itself: " + parent);
        }
        if (parent.isCash() || spunOff.isCash()) {
            throw new IllegalArgumentException("a spin off moves securities, not currency: "
                    + parent + " to " + spunOff);
        }
        if (!quantityPerParentShare.isPositive()) {
            throw new IllegalArgumentException("shares received per parent share must be positive, was "
                    + quantityPerParentShare);
        }
    }
}
