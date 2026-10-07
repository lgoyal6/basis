package com.basis.documents.application;

import com.basis.documents.domain.JobStatus;
import com.basis.documents.domain.PipelineStage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Stage adapters return JSON artifacts; database checkpoints and events are committed together. */
public final class DurablePipelineWorker {
    @FunctionalInterface public interface Stage { String run(JobLease lease) throws Exception; }
    private final ProcessingJobRepository jobs;
    private final String owner;
    private final Duration leaseDuration;
    private final int maxAttempts;
    private final Clock clock;

    public DurablePipelineWorker(ProcessingJobRepository jobs, String owner, Duration leaseDuration, int maxAttempts) {
        this(jobs, owner, leaseDuration, maxAttempts, Clock.systemUTC());
    }
    public DurablePipelineWorker(ProcessingJobRepository jobs, String owner, Duration leaseDuration, int maxAttempts, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs);
        this.owner = Objects.requireNonNull(owner);
        this.leaseDuration = Objects.requireNonNull(leaseDuration);
        this.clock = Objects.requireNonNull(clock);
        if (owner.isBlank() || leaseDuration.isZero() || leaseDuration.isNegative() || maxAttempts < 1) throw new IllegalArgumentException("invalid worker configuration");
        this.maxAttempts = maxAttempts;
    }

    public boolean runOnce(String tenantId, Map<PipelineStage, Stage> stages) {
        if (!stages.keySet().containsAll(java.util.List.of(PipelineStage.values()))) throw new IllegalArgumentException("every stage needs an adapter before claiming jobs");
        var claimed = jobs.claim(tenantId, owner, clock.instant(), leaseDuration);
        if (claimed.isEmpty()) return false;
        JobLease lease = claimed.get();
        if (lease.attempt() > maxAttempts) {
            jobs.fail(lease, "attempt budget exhausted after worker restart", JobStatus.DEAD, null, clock.instant());
            return true;
        }
        try {
            int first = lease.job().checkpoint() == null ? 0 : lease.job().checkpoint().ordinal() + 1;
            for (int i = first; i < PipelineStage.values().length; i++) {
                if (!jobs.heartbeat(lease, clock.instant(), leaseDuration)) return true;
                PipelineStage stage = PipelineStage.values()[i];
                String artifact = stages.get(stage).run(lease);
                if (!jobs.checkpoint(lease, stage, artifact, clock.instant())) return true;
            }
            jobs.complete(lease, clock.instant());
        } catch (Exception e) {
            JobStatus status = e instanceof IllegalArgumentException ? JobStatus.FAILED
                    : lease.attempt() >= maxAttempts ? JobStatus.DEAD : JobStatus.QUEUED;
            Instant retry = status == JobStatus.QUEUED ? clock.instant().plusSeconds(1L << Math.min(10, lease.attempt() - 1)) : null;
            jobs.fail(lease, e.getClass().getSimpleName(), status, retry, clock.instant());
        }
        return true;
    }
}
