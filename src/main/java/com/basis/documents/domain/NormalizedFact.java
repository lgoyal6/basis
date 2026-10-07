package com.basis.documents.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** A value derived from an immutable extraction. Corrections must use {@link #corrected}. */
public record NormalizedFact(
        String id, String issuer, String factType, String context, LocalDate periodStart,
        LocalDate periodEnd, BigDecimal value, String rawValue, FactUnit unit, String currency,
        String sourceDocument, String sourceVersion, String location, ExtractionMethod method,
        int confidencePermille, FactStatus status, String supersedesFactId) {
    public NormalizedFact {
        Objects.requireNonNull(id); Objects.requireNonNull(factType); Objects.requireNonNull(value);
        Objects.requireNonNull(unit); Objects.requireNonNull(status);
        if (confidencePermille < 0 || confidencePermille > 1000) throw new IllegalArgumentException("confidence must be 0..1000");
        if (periodEnd == null) throw new IllegalArgumentException("period end is required");
    }

    public NormalizedFact withStatus(FactStatus next) {
        if (!status.canTransitionTo(next)) throw new IllegalStateException(status + " cannot transition to " + next);
        return new NormalizedFact(id, issuer, factType, context, periodStart, periodEnd, value, rawValue, unit, currency,
                sourceDocument, sourceVersion, location, method, confidencePermille, next, supersedesFactId);
    }

    public static NormalizedFact corrected(NormalizedFact oldFact, String newId, BigDecimal newValue, String reason) {
        Objects.requireNonNull(reason); if (reason.isBlank()) throw new IllegalArgumentException("correction reason is required");
        if (!oldFact.status.canTransitionTo(FactStatus.SUPERSEDED)) throw new IllegalStateException("fact is not correctable");
        return new NormalizedFact(newId, oldFact.issuer, oldFact.factType, oldFact.context, oldFact.periodStart, oldFact.periodEnd,
                newValue, oldFact.rawValue, oldFact.unit, oldFact.currency, oldFact.sourceDocument, oldFact.sourceVersion,
                oldFact.location, oldFact.method, oldFact.confidencePermille, FactStatus.APPROVED, oldFact.id);
    }
}
