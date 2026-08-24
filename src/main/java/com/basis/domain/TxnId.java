package com.basis.domain;

import java.util.Objects;
import java.util.UUID;

/** Identity of a transaction. */
public record TxnId(UUID value) implements Comparable<TxnId> {

    public TxnId {
        Objects.requireNonNull(value, "value");
    }

    public static TxnId of(UUID value) {
        return new TxnId(value);
    }

    public static TxnId of(String value) {
        return new TxnId(UUID.fromString(value));
    }

    public static TxnId random() {
        return new TxnId(UUID.randomUUID());
    }

    @Override
    public int compareTo(TxnId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
