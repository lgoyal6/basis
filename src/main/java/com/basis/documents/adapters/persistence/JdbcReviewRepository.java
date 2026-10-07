package com.basis.documents.adapters.persistence;

import com.basis.documents.application.ReviewRepository;
import com.basis.documents.application.ReviewWorkflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import com.basis.documents.application.Outbox;
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
    private final ObjectMapper mapper;
    private final Outbox outbox;

    public JdbcReviewRepository(JdbcTemplate jdbc, ObjectMapper mapper, Outbox outbox) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.outbox = outbox;
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
    public ReviewWorkflow.Decision decide(String tenantId, String taskId, String key, ReviewWorkflow.Command command) {
        if (key == null || key.isBlank() || key.length() > 128) throw new IllegalArgumentException("Idempotency-Key must be 1..128 characters");
        // Map.of iteration order can change between JVMs. Sort keys so restart retries
        // use the same request digest as the process that accepted the command.
        var payload = new java.util.TreeMap<String, Object>();
        payload.put("taskId", taskId);
        payload.put("command", command);
        String request = encode(payload);
        String hash = hash(request);
        UUID tenant = uuid(tenantId);
        // The unique key serializes concurrent retries. The claim rolls back with the mutation.
        int inserted = jdbc.update("insert into idempotency_key (tenant_id, key, request_hash) values (?, ?, ?) on conflict do nothing", tenant, key, hash);
        if (inserted == 0) {
            var prior = jdbc.queryForMap("select request_hash, response_json from idempotency_key where tenant_id = ? and key = ? for update", tenant, key);
            if (!hash.equals(prior.get("request_hash"))) throw new IllegalStateException("idempotency key reused with a different request");
            if (prior.get("response_json") == null) throw new IllegalStateException("idempotency key has no replayable response");
            return decode(prior.get("response_json").toString());
        }
        ReviewTask task = jdbc.queryForObject("select id, fact_id, reason, version, status from review_task where tenant_id = ? and id = ? for update", this::task, tenant, uuid(taskId));
        jdbc.queryForObject("select id from normalized_fact where tenant_id = ? and id = ? for update", UUID.class, tenant, uuid(task.factId()));
        var fact = findFact(tenantId, task.factId());
        var decision = new ReviewWorkflow().decide(task, fact, command.expectedVersion(), command.action(), command.actor(), command.reason(), command.correction());
        appendDecisionInternal(tenantId, decision);
        String response = encode(decision);
        outbox.append(tenantId, "review.decision.recorded", response);
        jdbc.update("update idempotency_key set response_json = ?::jsonb, response_hash = ? where tenant_id = ? and key = ?", response, hash(response), tenant, key);
        return decision;
    }

    private String encode(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("invalid review command", e); }
    }
    private ReviewWorkflow.Decision decode(String value) {
        try { return mapper.readValue(value, ReviewWorkflow.Decision.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("stored response is invalid", e); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private void appendDecisionInternal(String tenantId, ReviewWorkflow.Decision decision) {
        UUID tenant = uuid(tenantId);
        UUID task = uuid(decision.task().id());
        String sourceFactId = decision.action() == ReviewAction.CORRECT
                ? decision.fact().supersedesFactId() : decision.fact().id();
        if (!decision.task().factId().equals(sourceFactId)) {
            throw new IllegalArgumentException("review task does not reference the supplied fact");
        }
        UUID fact = uuid(sourceFactId);
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
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
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
