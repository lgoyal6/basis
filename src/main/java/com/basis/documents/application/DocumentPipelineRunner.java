package com.basis.documents.application;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** One-process entry point for draining durable document jobs. */
@Service
public final class DocumentPipelineRunner {
    private final DurablePipelineWorker worker;
    private final DocumentPipelineService pipeline;

    public DocumentPipelineRunner(ProcessingJobRepository jobs, DocumentPipelineService pipeline,
                                  @Value("${basis.documents.worker-owner:local-worker}") String owner,
                                  @Value("${basis.documents.worker-lease-seconds:60}") long leaseSeconds,
                                  @Value("${basis.documents.worker-max-attempts:3}") int maxAttempts) {
        this.worker = new DurablePipelineWorker(jobs, owner, Duration.ofSeconds(leaseSeconds), maxAttempts, Clock.systemUTC());
        this.pipeline = pipeline;
    }

    public boolean runOnce(String tenantId) {
        return worker.runOnce(tenantId, pipeline::stagesFor);
    }
}
