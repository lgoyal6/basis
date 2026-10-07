package com.basis.documents.domain;

import java.time.Instant;
import java.util.Objects;

/** Small state machine used by both the JDBC worker and deterministic tests. */
public record ProcessingJob(String id, String idempotencyKey, JobStatus status, PipelineStage checkpoint,
                            int attempt, Instant nextRetryAt, String lastError) {
    public ProcessingJob {
        Objects.requireNonNull(id); Objects.requireNonNull(idempotencyKey); Objects.requireNonNull(status);
        if (attempt < 0) throw new IllegalArgumentException("attempt cannot be negative");
    }
    public ProcessingJob checkpoint(PipelineStage stage) {
        if (status != JobStatus.RUNNING) throw new IllegalStateException("job is not running");
        return new ProcessingJob(id, idempotencyKey, status, Objects.requireNonNull(stage), attempt, nextRetryAt, lastError);
    }
    public ProcessingJob retryAt(Instant when, String error) {
        if (status != JobStatus.RUNNING && status != JobStatus.FAILED && status != JobStatus.DEAD) throw new IllegalStateException("job is not retryable");
        return new ProcessingJob(id, idempotencyKey, JobStatus.QUEUED, checkpoint, attempt + 1, Objects.requireNonNull(when), error);
    }
}
