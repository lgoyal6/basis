package com.basis.documents.application;

import com.basis.documents.domain.ExtractedField;
import com.basis.documents.domain.ExtractionMethod;
import com.basis.documents.domain.FactStatus;
import com.basis.documents.domain.FactUnit;
import com.basis.documents.domain.NormalizedFact;
import com.basis.documents.domain.NumberNormalizer;
import com.basis.documents.domain.PipelineStage;
import com.basis.documents.domain.Reconciler;
import com.basis.documents.domain.ReconciliationResult;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Bridges durable job stages to immutable documents and append-only facts. */
@Service
public class DocumentPipelineService {
    private static final int REVIEW_THRESHOLD = 800;
    private final JdbcTemplate jdbc;
    private final DocumentObjectStore objects;
    private final List<DocumentExtractor> extractors;
    private final ObjectMapper mapper;

    public DocumentPipelineService(JdbcTemplate jdbc, DocumentObjectStore objects,
                                   List<DocumentExtractor> extractors, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.objects = objects;
        this.extractors = List.copyOf(extractors);
        this.mapper = mapper;
    }

    /** Returns all stage callbacks for one lease. The worker remains the only state machine. */
    public Map<PipelineStage, DurablePipelineWorker.Stage> stagesFor(JobLease lease) {
        var stages = new EnumMap<PipelineStage, DurablePipelineWorker.Stage>(PipelineStage.class);
        stages.put(PipelineStage.CLASSIFY, ignored -> classify(lease));
        stages.put(PipelineStage.LAYOUT, ignored -> extract(lease));
        stages.put(PipelineStage.TABLES, ignored -> countFields(lease, "tables"));
        stages.put(PipelineStage.CANDIDATES, ignored -> countFields(lease, "candidates"));
        stages.put(PipelineStage.NORMALIZE, ignored -> normalize(lease));
        stages.put(PipelineStage.VALIDATE, ignored -> validate(lease));
        stages.put(PipelineStage.RECONCILE, ignored -> reconcile(lease));
        stages.put(PipelineStage.ROUTE, ignored -> route(lease));
        return Map.copyOf(stages);
    }

    private String classify(JobLease lease) {
        var version = version(lease);
        return json(Map.of("versionId", version.id, "mediaType", version.mediaType, "filename", version.filename));
    }

    @Transactional
    private String extract(JobLease lease) {
        var version = version(lease);
        DocumentExtractor extractor = select(version.mediaType, version.filename);
        List<ExtractedField> fields;
        try (InputStream input = objects.get(lease.tenantId(), version.sha256)) {
            fields = extractor.extract(input, version.sha256);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("document object could not be read", e);
        }
        int stored = 0;
        for (int index = 0; index < fields.size(); index++) {
            ExtractedField field = fields.get(index);
            String id = UUID.nameUUIDFromBytes((lease.job().id() + "\u0000" + index)
                    .getBytes(StandardCharsets.UTF_8)).toString();
            jdbc.update("insert into extracted_field (id, tenant_id, version_id, fact_type, raw_value, unit, currency, period_start, period_end, location, method, confidence) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (id) do nothing",
                    UUID.fromString(id), UUID.fromString(lease.tenantId()), UUID.fromString(version.id), field.label(),
                    field.rawValue(), field.unit(), field.currency(), field.periodStart(), field.periodEnd(),
                    field.location(), field.method().name(), field.confidencePermille());
            stored++;
        }
        return json(Map.of("extractor", extractor.getClass().getSimpleName(), "fields", stored,
                "sourceVersion", version.sha256));
    }

    private String countFields(JobLease lease, String stage) {
        int count = jdbc.queryForObject("select count(*) from extracted_field e join processing_job j on j.version_id=e.version_id where j.id=? and j.tenant_id=?",
                Integer.class, UUID.fromString(lease.job().id()), UUID.fromString(lease.tenantId()));
        return json(Map.of("stage", stage, "fields", count));
    }

    @Transactional
    private String normalize(JobLease lease) {
        var version = version(lease);
        List<NormalizedFact> facts = new ArrayList<>();
        int[] skipped = {0};
        jdbc.query("select id, fact_type, raw_value, unit, currency, period_start, period_end, location, method, confidence from extracted_field where tenant_id=? and version_id=? order by id",
                rs -> {
                    String extractedId = rs.getString("id");
                    String raw = rs.getString("raw_value");
                    LocalDate periodEnd = rs.getObject("period_end", LocalDate.class);
                    if (periodEnd == null) { skipped[0]++; return; }
                    NormalizedFact fact;
                    try {
                        var value = NumberNormalizer.parse(raw, scale(rs.getString("unit")));
                        ExtractionMethod method = ExtractionMethod.valueOf(rs.getString("method"));
                        FactStatus status = method == ExtractionMethod.OCR || rs.getInt("confidence") < REVIEW_THRESHOLD
                                ? FactStatus.NEEDS_REVIEW : FactStatus.VERIFIED;
                        String id = UUID.nameUUIDFromBytes((lease.job().id() + "\u0000fact\u0000" + extractedId)
                                .getBytes(StandardCharsets.UTF_8)).toString();
                        fact = new NormalizedFact(id, null, rs.getString("fact_type"), "default",
                                rs.getObject("period_start", LocalDate.class), periodEnd, value, raw,
                                unit(rs.getString("unit")), rs.getString("currency"), version.documentKey,
                                version.sha256, rs.getString("location"), method, rs.getInt("confidence"), status, null);
                    } catch (IllegalArgumentException e) {
                        // An unparseable extraction remains immutable evidence. It is reported in
                        // the artifact instead of being coerced into a financial fact.
                        skipped[0]++;
                        return;
                    }
                    jdbc.update("insert into normalized_fact (id, tenant_id, extracted_field_id, issuer, fact_type, context, value, raw_value, unit, currency, period_start, period_end, source_document, source_version, location, extraction_method, confidence, status) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (id) do nothing",
                            UUID.fromString(fact.id()), UUID.fromString(lease.tenantId()), UUID.fromString(extractedId), fact.issuer(),
                            fact.factType(), fact.context(), fact.value(), fact.rawValue(), fact.unit().name(), fact.currency(),
                            fact.periodStart(), fact.periodEnd(), fact.sourceDocument(), fact.sourceVersion(), fact.location(),
                            fact.method().name(), fact.confidencePermille(), fact.status().name());
                    facts.add(fact);
                }, UUID.fromString(lease.tenantId()), UUID.fromString(version.id));
        return json(Map.of("normalized", facts.size(), "skipped", skipped[0],
                "reviewCandidates", facts.stream().filter(f -> f.status() == FactStatus.NEEDS_REVIEW).count()));
    }

    @Transactional
    private String validate(JobLease lease) {
        // Source-version hashes are not UUIDs, so validation is scoped by the job's version.
        int facts = jdbc.queryForObject("select count(*) from normalized_fact f join processing_job j on j.version_id=? and j.tenant_id=f.tenant_id where f.source_version = (select sha256 from document_version where id=j.version_id)",
                Integer.class, UUID.fromString(version(lease).id));
        return json(Map.of("validatedFacts", facts, "provenanceRequired", true));
    }

    private String reconcile(JobLease lease) {
        var current = version(lease);
        var left = jdbc.query("select id, issuer, fact_type, context, period_start, period_end, value, raw_value, unit, currency, source_document, source_version, location, extraction_method, confidence, status, supersedes_fact_id "
                        + "from normalized_fact where tenant_id=? and source_version=? and value is not null order by id",
                this::fact, UUID.fromString(lease.tenantId()), current.sha256);
        int compared = 0;
        int cases = 0;
        for (var a : left) {
            var right = jdbc.query("select id, issuer, fact_type, context, period_start, period_end, value, raw_value, unit, currency, source_document, source_version, location, extraction_method, confidence, status, supersedes_fact_id "
                            + "from normalized_fact where tenant_id=? and issuer is not distinct from ? and fact_type=? and context=? and period_end=? and source_version<>? and value is not null order by id",
                    this::fact, UUID.fromString(lease.tenantId()), a.issuer(), a.factType(), a.context(), a.periodEnd(), a.sourceVersion());
            for (var b : right) {
                compared++;
                ReconciliationResult result = Reconciler.compare(a, b);
                String first = a.id().compareTo(b.id()) < 0 ? a.id() : b.id();
                String second = a.id().compareTo(b.id()) < 0 ? b.id() : a.id();
                UUID caseId = UUID.nameUUIDFromBytes((lease.tenantId() + "\u0000case\u0000" + first + "\u0000" + second)
                        .getBytes(StandardCharsets.UTF_8));
                String explanation = json(Map.of("leftFactId", a.id(), "rightFactId", b.id(),
                        "classification", result.classification().name(), "explanation", result.explanation()));
                cases += jdbc.update("insert into reconciliation_case (id, tenant_id, classification, explanation, left_fact_id, right_fact_id) values (?, ?, ?, ?::jsonb, ?, ?) on conflict (id) do nothing",
                        caseId, UUID.fromString(lease.tenantId()), result.classification().name(), explanation, UUID.fromString(first), UUID.fromString(second));
            }
        }
        return json(Map.of("factsCompared", compared, "casesCreated", cases, "crossDocument", true));
    }

    private NormalizedFact fact(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
        return new NormalizedFact(rs.getString("id"), rs.getString("issuer"), rs.getString("fact_type"), rs.getString("context"),
                rs.getObject("period_start", LocalDate.class), rs.getObject("period_end", LocalDate.class), rs.getBigDecimal("value"),
                rs.getString("raw_value"), unit(rs.getString("unit")), rs.getString("currency"), rs.getString("source_document"),
                rs.getString("source_version"), rs.getString("location"), ExtractionMethod.valueOf(rs.getString("extraction_method")),
                rs.getInt("confidence"), FactStatus.valueOf(rs.getString("status")), rs.getString("supersedes_fact_id"));
    }

    @Transactional
    private String route(JobLease lease) {
        int routed = 0;
        var version = version(lease);
        var facts = jdbc.query("select id, status, confidence, extraction_method from normalized_fact where tenant_id=? and source_version=?",
                (rs, n) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("status"), rs.getInt("confidence"), rs.getString("extraction_method")},
                UUID.fromString(lease.tenantId()), version.sha256);
        for (Object[] fact : facts) {
            String status = (String) fact[1];
            if (!"NEEDS_REVIEW".equals(status)) continue;
            String reason = "OCR".equals(fact[3]) ? "OCR_SOURCE" : ((int) fact[2] < REVIEW_THRESHOLD ? "LOW_CONFIDENCE" : "PROVENANCE_UNVERIFIED");
            routed += jdbc.update("insert into review_task (id, tenant_id, fact_id, reason) values (?, ?, ?, ?) on conflict (tenant_id, fact_id, reason) do nothing",
                    UUID.nameUUIDFromBytes((lease.job().id() + "\u0000review\u0000" + fact[0] + "\u0000" + reason).getBytes(StandardCharsets.UTF_8)),
                    UUID.fromString(lease.tenantId()), fact[0], reason);
        }
        return json(Map.of("reviewTasksCreated", routed));
    }

    private DocumentExtractor select(String mediaType, String filename) {
        return extractors.stream().filter(extractor -> extractor.supports(mediaType, filename))
                .sorted(java.util.Comparator.comparingInt(this::priority)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no extractor accepts " + filename));
    }

    private int priority(DocumentExtractor extractor) {
        String name = extractor.getClass().getSimpleName();
        return name.startsWith("Pdf") ? 0 : name.startsWith("Xlsx") ? 1 : name.startsWith("Csv") ? 2 : 3;
    }

    private Version version(JobLease lease) {
        return jdbc.queryForObject("select v.id, v.sha256, v.filename, v.media_type, d.document_key from processing_job j join document_version v on v.id=j.version_id join document d on d.id=v.document_id where j.id=? and j.tenant_id=?",
                (rs, n) -> new Version(rs.getString("id"), rs.getString("sha256"), rs.getString("filename"), rs.getString("media_type"), rs.getString("document_key")),
                UUID.fromString(lease.job().id()), UUID.fromString(lease.tenantId()));
    }

    private static FactUnit unit(String value) {
        if (value == null || value.isBlank()) return FactUnit.OTHER;
        try { return FactUnit.valueOf(value.toUpperCase()); } catch (IllegalArgumentException e) { return FactUnit.OTHER; }
    }
    private static java.math.BigDecimal scale(String unit) {
        if (unit == null) return java.math.BigDecimal.ONE;
        return switch (unit.toLowerCase()) {
            case "thousands", "000s" -> java.math.BigDecimal.valueOf(1_000);
            case "millions", "mm" -> java.math.BigDecimal.valueOf(1_000_000);
            default -> java.math.BigDecimal.ONE;
        };
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("stage artifact could not be encoded", e); }
    }
    private record Version(String id, String sha256, String filename, String mediaType, String documentKey) { }
}
