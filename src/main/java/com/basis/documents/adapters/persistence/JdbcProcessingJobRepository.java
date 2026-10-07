package com.basis.documents.adapters.persistence;

import com.basis.documents.application.JobLease;
import com.basis.documents.application.Outbox;
import com.basis.documents.application.ProcessingJobRepository;
import com.basis.documents.domain.JobStatus;
import com.basis.documents.domain.PipelineStage;
import com.basis.documents.domain.ProcessingJob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcProcessingJobRepository implements ProcessingJobRepository {
    private final JdbcTemplate jdbc;
    private final Outbox outbox;

    public JdbcProcessingJobRepository(JdbcTemplate jdbc, Outbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Override
    public Optional<JobLease> claim(String tenantId, String owner, Instant now, Duration duration) {
        if (owner == null || owner.isBlank() || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("owner and a positive lease duration are required");
        }
        String sql = "with candidate as (select id from processing_job where tenant_id = ? and "
                + "((status = 'QUEUED' and (next_retry_at is null or next_retry_at <= ?)) "
                + "or (status = 'RUNNING' and lease_expires_at <= ?)) "
                + "order by created_at, id for update skip locked limit 1) "
                + "update processing_job j set status = 'RUNNING', lease_owner = ?, lease_expires_at = ?, "
                + "attempt = j.attempt + 1, lease_generation = j.lease_generation + 1 from candidate where j.id = candidate.id returning j.*";
        try {
            return Optional.of(jdbc.queryForObject(sql, this::lease, uuid(tenantId), time(now), time(now), owner, time(now.plus(duration))));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean heartbeat(JobLease lease, Instant now, Duration duration) {
        if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("positive lease duration required");
        return jdbc.update("update processing_job set lease_expires_at = ? where " + fence(),
                time(now.plus(duration)), uuid(lease.job().id()), uuid(lease.tenantId()), lease.owner(), lease.generation(), time(now)) == 1;
    }

    @Override
    @Transactional
    public boolean checkpoint(JobLease lease, PipelineStage stage, String artifactJson, Instant now) {
        PipelineStage previous = stage.ordinal() == 0 ? null : PipelineStage.values()[stage.ordinal() - 1];
        // The checkpoint, immutable output, and event share the same commit.
        int updated = jdbc.update("update processing_job set checkpoint = ? where " + fence()
                        + " and checkpoint is not distinct from ?",
                stage.name(), uuid(lease.job().id()), uuid(lease.tenantId()), lease.owner(), lease.generation(), time(now), previous == null ? null : previous.name());
        if (updated != 1) return false;
        jdbc.update("insert into document_stage_artifact (job_id, stage, payload) values (?, ?, ?::jsonb)", uuid(lease.job().id()), stage.name(), artifactJson);
        outbox.append(lease.tenantId(), "document.stage.completed", "{\"jobId\":\"" + lease.job().id() + "\",\"stage\":\"" + stage.name() + "\"}");
        return true;
    }

    @Override
    public java.util.Map<PipelineStage, String> artifacts(String tenantId, String jobId) {
        var result = new java.util.EnumMap<PipelineStage, String>(PipelineStage.class);
        jdbc.query("select a.stage, a.payload::text from document_stage_artifact a join processing_job j on j.id = a.job_id where j.tenant_id = ? and j.id = ?",
                rs -> { result.put(PipelineStage.valueOf(rs.getString(1)), rs.getString(2)); }, uuid(tenantId), uuid(jobId));
        return java.util.Map.copyOf(result);
    }

    @Override
    public boolean complete(JobLease lease, Instant now) {
        return jdbc.update("update processing_job set status = 'COMPLETED', last_error = null, next_retry_at = null, lease_owner = null, lease_expires_at = null where "
                        + fence() + " and checkpoint = 'ROUTE'",
                uuid(lease.job().id()), uuid(lease.tenantId()), lease.owner(), lease.generation(), time(now)) == 1;
    }

    @Override
    public boolean fail(JobLease lease, String error, JobStatus status, Instant retryAt, Instant now) {
        if (status != JobStatus.QUEUED && status != JobStatus.FAILED && status != JobStatus.DEAD) {
            throw new IllegalArgumentException("invalid failure status");
        }
        if (status == JobStatus.QUEUED && retryAt == null) throw new IllegalArgumentException("retry time required");
        return jdbc.update("update processing_job set status = ?, last_error = ?, next_retry_at = ?, lease_owner = null, lease_expires_at = null where " + fence(),
                status.name(), error, time(retryAt), uuid(lease.job().id()), uuid(lease.tenantId()), lease.owner(), lease.generation(), time(now)) == 1;
    }

    @Override
    public boolean cancel(String tenantId, String jobId) {
        return jdbc.update("update processing_job set status = 'CANCELLED', lease_owner = null, lease_expires_at = null where tenant_id = ? and id = ? and status in ('QUEUED','RUNNING')", uuid(tenantId), uuid(jobId)) == 1;
    }

    @Override
    public boolean retry(String tenantId, String jobId) {
        return jdbc.update("update processing_job set status = 'QUEUED', attempt = 0, next_retry_at = null, last_error = null where tenant_id = ? and id = ? and status in ('FAILED','DEAD','CANCELLED')", uuid(tenantId), uuid(jobId)) == 1;
    }

    private static String fence() {
        return "id = ? and tenant_id = ? and lease_owner = ? and lease_generation = ? and status = 'RUNNING' and lease_expires_at > ?";
    }

    private JobLease lease(ResultSet rs, int ignored) throws SQLException {
        ProcessingJob job = new ProcessingJob(rs.getString("id"), rs.getString("idempotency_key"),
                JobStatus.valueOf(rs.getString("status")), rs.getString("checkpoint") == null ? null : PipelineStage.valueOf(rs.getString("checkpoint")),
                rs.getInt("attempt"), instant(rs.getTimestamp("next_retry_at")), rs.getString("last_error"));
        return new JobLease(job, rs.getString("tenant_id"), rs.getString("lease_owner"), rs.getInt("attempt"), rs.getLong("lease_generation"), instant(rs.getTimestamp("lease_expires_at")));
    }

    private static Timestamp time(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static UUID uuid(String value) { return UUID.fromString(value); }
}
