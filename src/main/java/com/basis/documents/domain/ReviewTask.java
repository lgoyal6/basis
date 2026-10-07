package com.basis.documents.domain;

import java.util.Objects;

public record ReviewTask(String id, String factId, String reason, int version, boolean open) {
    public ReviewTask {
        Objects.requireNonNull(id); Objects.requireNonNull(factId); Objects.requireNonNull(reason);
        if (version < 0) throw new IllegalArgumentException("version cannot be negative");
    }
    public ReviewTask close() { return new ReviewTask(id, factId, reason, version + 1, false); }
}
