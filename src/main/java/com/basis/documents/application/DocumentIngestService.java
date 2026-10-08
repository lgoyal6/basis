package com.basis.documents.application;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores an original exactly once and creates the durable pipeline command. */
@Service
public class DocumentIngestService {
    private final JdbcTemplate jdbc;
    private final DocumentObjectStore objects;
    public DocumentIngestService(JdbcTemplate jdbc, DocumentObjectStore objects) { this.jdbc = jdbc; this.objects = objects; }

    @Transactional
    public Receipt ingest(String tenantId, String documentKey, String filename, String mediaType, long size, InputStream input, String idempotencyKey) {
        if (size <= 0 || size > 50L * 1024 * 1024) throw new IllegalArgumentException("unsupported document size");
        try {
            byte[] bytes = input.readNBytes((int) size + 1);
            if (bytes.length != size) throw new IllegalArgumentException("document size changed while reading");
            return ingestBytes(tenantId, documentKey, filename, mediaType, bytes, idempotencyKey);
        } catch (java.io.IOException e) { throw new IllegalArgumentException("document could not be read", e); }
    }

    /** Ingest from a repeatable stream after the multipart adapter has bounded it. */
    @Transactional
    public Receipt ingestBytes(String tenantId, String documentKey, String filename, String mediaType, byte[] bytes, String idempotencyKey) {
        requireText(documentKey, "documentKey", 256);
        requireText(filename, "filename", 255);
        requireText(idempotencyKey, "Idempotency-Key", 128);
        // Only registered extractors may accept an upload. Broader formats are enabled
        // together with their adapters, rather than failing later in a background job.
        if (!"text/csv".equals(mediaType)) throw new IllegalArgumentException("unsupported format; upload text/csv");
        if (bytes == null || bytes.length == 0 || bytes.length > 50 * 1024 * 1024) throw new IllegalArgumentException("document size is unsupported");
        UUID tenant = uuid(tenantId);
        String hash = sha256(bytes);
        String requestHash = requestHash(documentKey, filename, mediaType, hash);
        // Serialize ingest per tenant. The lock covers retry aliases, content deduplication,
        // job creation, and outbox insertion, and is released on rollback or commit.
        jdbc.queryForObject("select id from doc_tenant where id = ? for update", UUID.class, tenant);
        var prior = jdbc.queryForList("select r.request_hash, r.job_id from document_ingest_request r where r.tenant_id = ? and r.key = ?", tenant, idempotencyKey);
        if (!prior.isEmpty()) {
            if (!requestHash.equals(prior.getFirst().get("request_hash"))) throw new IllegalStateException("idempotency key reused with a different upload");
            return new Receipt(prior.getFirst().get("job_id").toString(), hash, false);
        }
        // Jobs written before retry aliases existed still bind their original upload.
        var legacy = jdbc.queryForList("select j.id, v.sha256, v.filename, v.media_type, d.document_key from processing_job j join document_version v on v.id=j.version_id join document d on d.id=v.document_id where j.tenant_id=? and j.idempotency_key=?", tenant, idempotencyKey);
        if (!legacy.isEmpty()) {
            var row = legacy.getFirst();
            if (!requestHash.equals(requestHash(row.get("document_key").toString(), row.get("filename").toString(), row.get("media_type").toString(), row.get("sha256").toString()))) throw new IllegalStateException("idempotency key reused with a different upload");
            UUID job = (UUID) row.get("id");
            remember(tenant, idempotencyKey, requestHash, job);
            return new Receipt(job.toString(), hash, false);
        }
        var duplicates = jdbc.query("select j.id from processing_job j join document_version v on v.id=j.version_id where v.tenant_id=? and v.sha256=? order by j.created_at,j.id limit 1", (rs, n) -> rs.getObject(1, UUID.class), tenant, hash);
        if (!duplicates.isEmpty()) {
            UUID job = duplicates.getFirst();
            remember(tenant, idempotencyKey, requestHash, job);
            return new Receipt(job.toString(), hash, false);
        }
        DocumentObjectStore.Stored object = objects.put(tenantId, hash, new java.io.ByteArrayInputStream(bytes), bytes.length);
        UUID document = UUID.randomUUID(), version = UUID.randomUUID(), job = UUID.randomUUID();
        jdbc.update("insert into document(id,tenant_id,document_key) values (?,?,?) on conflict (tenant_id,document_key) do nothing", document, tenant, documentKey);
        UUID actual = jdbc.queryForObject("select id from document where tenant_id = ? and document_key = ?", UUID.class, tenant, documentKey);
        jdbc.update("insert into document_version(id,tenant_id,document_id,sha256,filename,media_type,size_bytes,object_key,correlation_id) values (?,?,?,?,?,?,?,?,?) on conflict (tenant_id,sha256) do nothing", version, tenant, actual, hash, filename, mediaType, bytes.length, object.key(), job.toString());
        UUID actualVersion = jdbc.queryForObject("select id from document_version where tenant_id = ? and sha256 = ?", UUID.class, tenant, hash);
        jdbc.update("insert into processing_job(id,tenant_id,version_id,idempotency_key,correlation_id) values (?,?,?,?,?)", job, tenant, actualVersion, idempotencyKey, job.toString());
        jdbc.update("insert into outbox_event(id,tenant_id,event_type,payload) values (?,?,?,?::jsonb)", UUID.randomUUID(), tenant, "document.ingested", "{\"jobId\":\"" + job + "\",\"versionId\":\"" + actualVersion + "\"}");
        remember(tenant, idempotencyKey, requestHash, job);
        return new Receipt(job.toString(), hash, true);
    }

    private void remember(UUID tenant, String key, String hash, UUID job) {
        jdbc.update("insert into document_ingest_request(tenant_id,key,request_hash,job_id) values (?,?,?,?)", tenant, key, hash, job);
    }

    private static void requireText(String value, String field, int limit) {
        if (value == null || value.isBlank() || value.length() > limit || value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException(field + " must be 1.." + limit + " characters without control characters");
    }

    private static String requestHash(String... fields) {
        var request = new StringBuilder();
        for (String field : fields) request.append(field.length()).append(':').append(field);
        return sha256(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public record Receipt(String jobId, String sha256, boolean created) { }
    private static UUID uuid(String value) { try { return UUID.fromString(value); } catch (RuntimeException e) { throw new IllegalArgumentException("tenant id is invalid", e); } }
    private static String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception e) { throw new IllegalStateException(e); } }
}
