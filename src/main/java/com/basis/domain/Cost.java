package com.basis.domain;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The cost annotation that pins a security posting to an acquisition lot: the
 * {@code {150.00 USD, 2026-01-15, lot#a1}} in the ledger notation.
 *
 * <p>This is the pin, not the lot. It carries the lot's terms so that a posting is
 * self-describing on replay without a join, and so that a disposal cannot silently
 * be re-priced against a lot whose terms changed.
 */
public record Cost(LotId lotId, Price unitCost, LocalDate acquisitionDate) {

    public Cost {
        Objects.requireNonNull(lotId, "lotId");
        Objects.requireNonNull(unitCost, "unitCost");
        Objects.requireNonNull(acquisitionDate, "acquisitionDate");
        if (unitCost.isNegative()) {
            throw new IllegalArgumentException("unit cost must not be negative: " + unitCost);
        }
    }

    @Override
    public String toString() {
        return "{" + unitCost + ", " + acquisitionDate + ", " + lotId + "}";
    }
}
