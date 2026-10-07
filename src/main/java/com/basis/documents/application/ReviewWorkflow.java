package com.basis.documents.application;

import com.basis.documents.domain.FactStatus;
import com.basis.documents.domain.NormalizedFact;
import com.basis.documents.domain.ReviewAction;
import com.basis.documents.domain.ReviewTask;
import java.math.BigDecimal;
import java.util.Objects;

/** Domain-level review command handler. Persistence adapters can make the version check atomic. */
public final class ReviewWorkflow {
    public record Decision(ReviewTask task, NormalizedFact fact, ReviewAction action, String actor, String reason) { }

    public Decision decide(ReviewTask task, NormalizedFact fact, int expectedVersion, ReviewAction action,
                           String actor, String reason, BigDecimal correction) {
        Objects.requireNonNull(task); Objects.requireNonNull(fact); Objects.requireNonNull(action);
        if (!task.open()) throw new IllegalStateException("review task is already closed");
        if (task.version() != expectedVersion) throw new IllegalStateException("stale review task version");
        if (actor == null || actor.isBlank() || reason == null || reason.isBlank()) throw new IllegalArgumentException("actor and reason are required");
        NormalizedFact next = switch (action) {
            case APPROVE -> fact.withStatus(FactStatus.APPROVED);
            case REJECT -> fact.withStatus(FactStatus.REJECTED);
            case MARK_SOURCE_UNUSABLE -> fact.withStatus(FactStatus.SOURCE_UNUSABLE);
            case CORRECT -> {
                if (correction == null) throw new IllegalArgumentException("correction value is required");
                yield NormalizedFact.corrected(fact, fact.id() + "-correction", correction, reason);
            }
            case MERGE, REQUEST_REPROCESSING -> throw new UnsupportedOperationException(action + " requires a repository use case");
        };
        return new Decision(task.close(), next, action, actor, reason);
    }
}
