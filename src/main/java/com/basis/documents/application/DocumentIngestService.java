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
        if (bytes == null || bytes.length == 0 || bytes.length > 50 * 1024 * 1024) throw new IllegalArgumentException("document size is unsupported");
        UUID tenant = uuid(tenantId); String hash = sha256(bytes);
        var prior = jdbc.query("select id from processing_job where tenant_id = ? and idempotency_key = ?", (rs, n) -> rs.getString(1), tenant, idempotencyKey);
        if (!prior.isEmpty()) return new Receipt(prior.getFirst(), hash, false);
        DocumentObjectStore.Stored object = objects.put(tenantId, hash, new java.io.ByteArrayInputStream(bytes), bytes.length);
        UUID document = UUID.randomUUID(), version = UUID.randomUUID(), job = UUID.randomUUID();
        jdbc.update("insert into document(id,tenant_id,document_key) values (?,?,?) on conflict (tenant_id,document_key) do nothing", document, tenant, documentKey);
        UUID actual = jdbc.queryForObject("select id from document where tenant_id = ? and document_key = ?", UUID.class, tenant, documentKey);
        jdbc.update("insert into document_version(id,tenant_id,document_id,sha256,filename,media_type,size_bytes,object_key,correlation_id) values (?,?,?,?,?,?,?,?,?) on conflict (tenant_id,sha256) do nothing", version, tenant, actual, hash, filename, mediaType, bytes.length, object.key(), job.toString());
        UUID actualVersion = jdbc.queryForObject("select id from document_version where tenant_id = ? and sha256 = ?", UUID.class, tenant, hash);
        jdbc.update("insert into processing_job(id,tenant_id,version_id,idempotency_key,correlation_id) values (?,?,?,?,?)", job, tenant, actualVersion, idempotencyKey, job.toString());
        jdbc.update("insert into outbox_event(id,tenant_id,event_type,payload) values (?,?,?,?::jsonb)", UUID.randomUUID(), tenant, "document.ingested", "{\"jobId\":\"" + job + "\",\"versionId\":\"" + actualVersion + "\"}");
        return new Receipt(job.toString(), hash, true);
    }
    public record Receipt(String jobId, String sha256, boolean created) { }
    private static UUID uuid(String value) { try { return UUID.fromString(value); } catch (RuntimeException e) { throw new IllegalArgumentException("tenant id is invalid", e); } }
    private static String sha256(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception e) { throw new IllegalStateException(e); } }
}
