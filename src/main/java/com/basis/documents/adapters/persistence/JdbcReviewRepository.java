package com.basis.documents.adapters.persistence;

import com.basis.documents.application.ReviewRepository;
import com.basis.documents.application.ReviewWorkflow;
import com.basis.documents.domain.ExtractionMethod;
import com.basis.documents.domain.FactStatus;
import com.basis.documents.domain.FactUnit;
import com.basis.documents.domain.NormalizedFact;
import com.basis.documents.domain.ReviewAction;
import com.basis.documents.domain.ReviewTask;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL adapter for the append-only document review workflow. */
@Repository
public class JdbcReviewRepository implements ReviewRepository {
    private final JdbcTemplate jdbc;

    public JdbcReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ReviewTask findTask(String tenantId, String taskId) {
        return jdbc.queryForObject(
                "select id, fact_id, reason, version, status from review_task where tenant_id = ? and id = ?",
                this::task, uuid(tenantId), uuid(taskId));
    }

    @Override
    public NormalizedFact findFact(String tenantId, String factId) {
        return jdbc.queryForObject(
                "select id, issuer, fact_type, context, period_start, period_end, value, raw_value, unit, currency, "
                        + "source_document, source_version, location, extraction_method, confidence, status, supersedes_fact_id "
                        + "from normalized_fact where tenant_id = ? and id = ?",
                this::fact, uuid(tenantId), uuid(factId));
    }

    @Override
    @Transactional
    public void appendDecision(String tenantId, ReviewWorkflow.Decision decision) {
        UUID tenant = uuid(tenantId);
        UUID task = uuid(decision.task().id());
        UUID fact = uuid(decision.fact().supersedesFactId() == null ? decision.fact().id() : decision.fact().supersedesFactId());
        String previousStatus = jdbc.queryForObject(
                "select status from normalized_fact where tenant_id = ? and id = ? for update",
                String.class, tenant, fact);

        int closed = jdbc.update(
                "update review_task set status = 'CLOSED', version = version + 1 where tenant_id = ? and id = ? "
                        + "and status = 'OPEN' and version = ?",
                tenant, task, decision.task().version() - 1);
        if (closed != 1) throw new IllegalStateException("stale review task version");

        if (decision.action() == ReviewAction.CORRECT) {
            jdbc.update("update normalized_fact set status = 'SUPERSEDED' where tenant_id = ? and id = ?",
                    tenant, fact);
            insertFact(tenant, decision.fact());
        } else {
            jdbc.update("update normalized_fact set status = ? where tenant_id = ? and id = ?",
                    decision.fact().status().name(), tenant, fact);
        }

        jdbc.update("insert into review_decision (id, tenant_id, task_id, fact_id, actor, action, reason, previous_status, new_status) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), tenant, task, fact, decision.actor(), decision.action().name(), decision.reason(),
                previousStatus, decision.fact().status().name());
    }

    private void insertFact(UUID tenant, NormalizedFact fact) {
        jdbc.update("insert into normalized_fact (id, tenant_id, issuer, fact_type, context, value, raw_value, unit, currency, "
                        + "period_start, period_end, source_document, source_version, location, extraction_method, confidence, status, supersedes_fact_id) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                uuid(fact.id()), tenant, fact.issuer(), fact.factType(), fact.context(), fact.value(), fact.rawValue(), fact.unit().name(),
                fact.currency(), fact.periodStart(), fact.periodEnd(), fact.sourceDocument(), fact.sourceVersion(), fact.location(),
                fact.method().name(), fact.confidencePermille(), fact.status().name(),
                fact.supersedesFactId() == null ? null : uuid(fact.supersedesFactId()));
    }

    private ReviewTask task(ResultSet rs, int ignored) throws SQLException {
        return new ReviewTask(rs.getString("id"), rs.getString("fact_id"), rs.getString("reason"), rs.getInt("version"),
                "OPEN".equals(rs.getString("status")));
    }

    private NormalizedFact fact(ResultSet rs, int ignored) throws SQLException {
        return new NormalizedFact(rs.getString("id"), rs.getString("issuer"), rs.getString("fact_type"), rs.getString("context"),
                rs.getObject("period_start", java.time.LocalDate.class), rs.getObject("period_end", java.time.LocalDate.class),
                rs.getBigDecimal("value"), rs.getString("raw_value"), FactUnit.valueOf(rs.getString("unit")), rs.getString("currency"),
                rs.getString("source_document"), rs.getString("source_version"), rs.getString("location"),
                ExtractionMethod.valueOf(rs.getString("extraction_method")), rs.getInt("confidence"),
                FactStatus.valueOf(rs.getString("status")), rs.getString("supersedes_fact_id"));
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("document identifiers must be UUIDs", e);
        }
    }
}
