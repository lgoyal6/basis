package com.basis.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of an acquisition lot. Opaque and stable: it is written into
 * {@code posting.lot_id} and is what makes specific-lot disposal and replay
 * determinism possible.
 */
public record LotId(String value) implements Comparable<LotId> {

    public LotId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("lot id must not be blank");
        }
    }

    public static LotId of(String value) {
        return new LotId(value);
    }

    public static LotId random() {
        return new LotId(UUID.randomUUID().toString());
    }

    @Override
    public int compareTo(LotId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
