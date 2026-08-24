package com.basis.domain;

import java.util.Objects;

/** A named lot and how much of it a disposal intends to consume. */
public record SpecificLotRequest(LotId lotId, Quantity quantity) {

    public SpecificLotRequest {
        Objects.requireNonNull(lotId, "lotId");
        Objects.requireNonNull(quantity, "quantity");
        if (!quantity.isPositive()) {
            throw new IllegalArgumentException("specific lot quantity must be positive, was " + quantity);
        }
    }
}
