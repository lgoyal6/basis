package com.basis.documents.application;

import com.basis.documents.domain.JobStatus;
import com.basis.documents.domain.PipelineStage;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface ProcessingJobRepository {
    Optional<JobLease> claim(String tenantId, String owner, Instant now, Duration leaseDuration);
    boolean heartbeat(JobLease lease, Instant now, Duration leaseDuration);
    boolean checkpoint(JobLease lease, PipelineStage stage, String artifactJson, Instant now);
    java.util.Map<PipelineStage, String> artifacts(String tenantId, String jobId);
    boolean complete(JobLease lease, Instant now);
    boolean fail(JobLease lease, String error, JobStatus status, Instant retryAt, Instant now);
    boolean cancel(String tenantId, String jobId);
    boolean retry(String tenantId, String jobId);
}
