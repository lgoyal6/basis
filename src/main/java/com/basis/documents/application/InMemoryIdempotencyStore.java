package com.basis.documents.application;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryIdempotencyStore implements IdempotencyStore {
    private final Set<String> keys = ConcurrentHashMap.newKeySet();
    @Override public boolean claim(String tenantId, String key) {
        if (tenantId == null || key == null || key.isBlank()) throw new IllegalArgumentException("tenant and idempotency key are required");
        return keys.add(tenantId + "\u0000" + key);
    }
}
