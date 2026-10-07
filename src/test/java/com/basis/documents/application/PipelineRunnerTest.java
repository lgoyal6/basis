package com.basis.documents.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.basis.documents.domain.JobStatus;
import com.basis.documents.domain.PipelineStage;
import com.basis.documents.domain.ProcessingJob;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PipelineRunnerTest {
    @Test void completedStagesAreReusedAndFailureIsVisible() {
        var counts = new AtomicInteger(); var stages = new HashMap<PipelineStage, PipelineRunner.Stage>();
        for (var stage : PipelineStage.values()) stages.put(stage, counts::incrementAndGet);
        stages.put(PipelineStage.TABLES, () -> { throw new IllegalStateException("bad table"); });
        var job = new ProcessingJob("j", "key", JobStatus.RUNNING, null, 1, null, null);
        var result = new PipelineRunner().run(job, EnumSet.of(PipelineStage.CLASSIFY), stages);
        assertThat(result.reused()).containsExactly(PipelineStage.CLASSIFY);
        assertThat(result.job().status()).isEqualTo(JobStatus.FAILED);
        assertThat(result.job().lastError()).isEqualTo("bad table");
        assertThat(counts).hasValue(1);
    }

    @Test void allStagesCompleteOnlyAfterEveryStageRuns() {
        var stages = new HashMap<PipelineStage, PipelineRunner.Stage>();
        for (var stage : PipelineStage.values()) stages.put(stage, () -> { });
        var job = new ProcessingJob("j", "key", JobStatus.RUNNING, null, 0, null, null);
        assertThat(new PipelineRunner().run(job, EnumSet.noneOf(PipelineStage.class), stages).job().status()).isEqualTo(JobStatus.COMPLETED);
    }
}
