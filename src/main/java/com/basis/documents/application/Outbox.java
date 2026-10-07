package com.basis.documents.application;

public interface Outbox {
    void append(String tenantId, String type, String payload);
}
