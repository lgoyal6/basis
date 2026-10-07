package com.basis.documents.application;

import com.basis.documents.domain.JobStatus;
import com.basis.documents.domain.PipelineStage;
import com.basis.documents.domain.ProcessingJob;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Executes stages in order and skips checkpoints already committed by a prior worker. */
public final class PipelineRunner {
    @FunctionalInterface public interface Stage { void run() throws Exception; }
    public record Result(ProcessingJob job, Set<PipelineStage> reused) { }

    public Result run(ProcessingJob initial, Set<PipelineStage> completed, java.util.Map<PipelineStage, Stage> stages) {
        Objects.requireNonNull(initial); Objects.requireNonNull(completed); Objects.requireNonNull(stages);
        ProcessingJob job = initial;
        Set<PipelineStage> reused = EnumSet.noneOf(PipelineStage.class);
        for (PipelineStage stage : PipelineStage.values()) {
            if (completed.contains(stage)) { reused.add(stage); continue; }
            try {
                Stage work = stages.get(stage); if (work == null) throw new IllegalArgumentException("missing stage " + stage);
                work.run();
                job = new ProcessingJob(job.id(), job.idempotencyKey(), JobStatus.RUNNING, stage, job.attempt(), null, null);
            } catch (Exception e) {
                job = new ProcessingJob(job.id(), job.idempotencyKey(), JobStatus.FAILED, job.checkpoint(), job.attempt(), Instant.now(), e.getMessage());
                return new Result(job, Set.copyOf(reused));
            }
        }
        job = new ProcessingJob(job.id(), job.idempotencyKey(), JobStatus.COMPLETED, job.checkpoint(), job.attempt(), null, null);
        return new Result(job, Set.copyOf(reused));
    }
}
