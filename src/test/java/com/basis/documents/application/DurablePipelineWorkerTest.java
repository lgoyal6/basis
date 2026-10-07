package com.basis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.basis.documents.domain.JobStatus;
import com.basis.documents.domain.PipelineStage;
import com.basis.documents.domain.ProcessingJob;
import java.time.Duration;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DurablePipelineWorkerTest {
    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");

    @Test
    void checkpointsEveryStageAndCompletesAfterRestart() {
        FakeJobs jobs = new FakeJobs();
        AtomicInteger ran = new AtomicInteger();
        Map<PipelineStage, DurablePipelineWorker.Stage> stages = stages(ran);

        var worker = new DurablePipelineWorker(jobs, "worker-a", Duration.ofMinutes(1), 3, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(worker.runOnce("tenant", stages)).isTrue();
        assertThat(jobs.completed).isTrue();
        assertThat(ran.get()).isEqualTo(PipelineStage.values().length);
        assertThat(jobs.checkpoints).containsExactly(PipelineStage.values());
    }

    @Test
    void aFailureIsRetryableAndDoesNotPretendTheJobCompleted() {
        FakeJobs jobs = new FakeJobs();
        Map<PipelineStage, DurablePipelineWorker.Stage> stages = stages(new AtomicInteger());
        stages.put(PipelineStage.CLASSIFY, lease -> { throw new IllegalStateException("parser timeout"); });

        new DurablePipelineWorker(jobs, "worker-a", Duration.ofMinutes(1), 3, Clock.fixed(NOW, ZoneOffset.UTC)).runOnce("tenant", stages);

        assertThat(jobs.failure).isEqualTo("IllegalStateException");
        assertThat(jobs.failureStatus).isEqualTo(JobStatus.QUEUED);
        assertThat(jobs.completed).isFalse();
    }

    private static Map<PipelineStage, DurablePipelineWorker.Stage> stages(AtomicInteger ran) {
        Map<PipelineStage, DurablePipelineWorker.Stage> stages = new EnumMap<>(PipelineStage.class);
        for (PipelineStage stage : PipelineStage.values()) stages.put(stage, lease -> { ran.incrementAndGet(); return "{}"; });
        return stages;
    }

    private static final class FakeJobs implements ProcessingJobRepository {
        private final JobLease lease = new JobLease(new ProcessingJob("job", "key", JobStatus.QUEUED, null, 0, null, null), "tenant", "worker-a", 1, 1, NOW.plusSeconds(60));
        private final java.util.List<PipelineStage> checkpoints = new java.util.ArrayList<>();
        private boolean claimed;
        private boolean completed;
        private String failure;
        private JobStatus failureStatus;

        @Override public Optional<JobLease> claim(String tenantId, String owner, Instant now, Duration leaseDuration) {
            if (claimed) return Optional.empty();
            claimed = true;
            return Optional.of(lease);
        }
        @Override public boolean heartbeat(JobLease lease, Instant now, Duration leaseDuration) { return true; }
        @Override public boolean checkpoint(JobLease lease, PipelineStage stage, String artifactJson, Instant now) { checkpoints.add(stage); return true; }
        @Override public Map<PipelineStage, String> artifacts(String tenantId, String jobId) { return Map.of(); }
        @Override public boolean complete(JobLease lease, Instant now) { completed = true; return true; }
        @Override public boolean cancel(String tenantId, String jobId) { return true; }
        @Override public boolean retry(String tenantId, String jobId) { return true; }
        @Override public boolean fail(JobLease lease, String error, JobStatus status, Instant retryAt, Instant now) {
            failure = error;
            failureStatus = status;
            return true;
        }
    }
}
