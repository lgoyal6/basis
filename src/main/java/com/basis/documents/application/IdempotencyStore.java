package com.basis.documents.application;

public interface IdempotencyStore {
    boolean claim(String tenantId, String key);
}
