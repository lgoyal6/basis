package com.basis.documents.application;

import com.basis.documents.domain.ProcessingJob;
import java.time.Instant;

/** The monotonic generation fences restarted workers, even after retry resets their attempt budget. */
public record JobLease(ProcessingJob job, String tenantId, String owner, int attempt, long generation, Instant expiresAt) {
    public JobLease {
        if (job == null || tenantId == null || owner == null || owner.isBlank() || attempt < 1 || generation < 1 || expiresAt == null) {
            throw new IllegalArgumentException("invalid job lease");
        }
    }
}
