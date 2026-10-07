package com.basis.documents.domain;

public record ReconciliationResult(ReconciliationClass classification, String explanation) {
    public ReconciliationResult {
        if (explanation == null || explanation.isBlank()) throw new IllegalArgumentException("explanation is required");
    }
}
