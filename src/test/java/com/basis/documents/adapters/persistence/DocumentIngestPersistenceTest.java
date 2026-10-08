package com.basis.documents.adapters.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.basis.documents.application.DocumentIngestService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest(properties = {"spring.main.web-application-type=servlet", "basis.documents.actor=test", "basis.documents.role=UPLOADER"})
@ActiveProfiles("workbench")
@AutoConfigureMockMvc
@Testcontainers
class DocumentIngestPersistenceTest {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final UUID TENANT = UUID.randomUUID();
    private static final java.nio.file.Path ROOT;
    static {
        try { ROOT = java.nio.file.Files.createTempDirectory("basis-ingest-test-"); }
        catch (java.io.IOException e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("basis.documents.tenant-id", TENANT::toString);
        r.add("basis.documents.token-digest", () -> "4c5dc9b7708905f77f5e5d16316b5dfb425e68cb326dcd55a860e90a7707031e");
        r.add("basis.documents.object-root", ROOT::toString);
    }
    @Autowired JdbcTemplate db;
    @Autowired DocumentIngestService service;
    @Autowired MockMvc api;
    private final byte[] bytes = "revenue,10,2025-12-31\n".getBytes(StandardCharsets.UTF_8);

    @BeforeEach void seed() {
        db.execute("truncate doc_tenant cascade");
        db.update("insert into doc_tenant(id,name) values (?,?)", TENANT, "test");
    }
    @AfterAll static void cleanup() throws Exception {
        try (var paths = java.nio.file.Files.walk(ROOT)) {
            for (var p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(p);
        }
    }
    private DocumentIngestService.Receipt upload(String key, byte[] content) {
        return service.ingest(TENANT.toString(), "annual-report", "report.csv", "text/csv", content.length, new ByteArrayInputStream(content), key);
    }
    @Test void retriesAndContentDuplicatesReuseOneJobAndOneOutboxEvent() {
        var first = upload("key-1", bytes);
        assertThat(first.created()).isTrue();
        var replay = upload("key-1", bytes);
        assertThat(replay.jobId()).isEqualTo(first.jobId());
        assertThat(replay.created()).isFalse();
        var alias = upload("key-2", bytes);
        assertThat(alias.jobId()).isEqualTo(first.jobId());
        assertThat(alias.created()).isFalse();
        assertThat(upload("key-2", bytes)).isEqualTo(alias);
        assertThatThrownBy(() -> upload("key-1", "revenue,11\n".getBytes(StandardCharsets.UTF_8))).isInstanceOf(IllegalStateException.class).hasMessageContaining("different upload");
        assertThat(db.queryForObject("select count(*) from processing_job", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from outbox_event", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from document_version", Integer.class)).isEqualTo(1);
    }
    @Test void simultaneousRetriesReturnOneReceiptWithoutUniqueConstraintFailure() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<DocumentIngestService.Receipt> run = () -> { start.await(); return upload("same", bytes); };
            var a = pool.submit(run); var b = pool.submit(run); start.countDown();
            var ra = a.get(20, TimeUnit.SECONDS); var rb = b.get(20, TimeUnit.SECONDS);
            assertThat(ra.jobId()).isEqualTo(rb.jobId());
            assertThat(ra.created()).isNotEqualTo(rb.created());
        }
    }
    @Test void outboxFailureRollsBackTheMultipartEntryTransaction() {
        db.execute("alter table outbox_event add constraint reject_ingest check(event_type <> 'document.ingested')");
        try {
            assertThatThrownBy(() -> upload("fails", bytes)).isInstanceOf(RuntimeException.class);
            assertThat(db.queryForObject("select count(*) from processing_job", Integer.class)).isZero();
            assertThat(db.queryForObject("select count(*) from document_version", Integer.class)).isZero();
            assertThat(db.queryForObject("select count(*) from document", Integer.class)).isZero();
        } finally { db.execute("alter table outbox_event drop constraint reject_ingest"); }
        assertThat(upload("fails", bytes).created()).isTrue();
    }
    @Test void invalidMetadataAndUnsupportedFormatsAreRejectedBeforePersistence() {
        assertThatThrownBy(() -> upload(null, bytes)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingestBytes(TENANT.toString(), "", "file.csv", "text/csv", bytes, "key")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.ingestBytes(TENANT.toString(), "report", "file.exe", "application/octet-stream", bytes, "key")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsupported format");
        assertThat(db.queryForObject("select count(*) from processing_job", Integer.class)).isZero();
    }
    @Test void workbenchContextStartsAndMultipartUploadRejectsConflictingRetry() throws Exception {
        var file = new MockMultipartFile("file", "report.csv", "text/csv", bytes);
        api.perform(multipart("/v1/workbench/documents").file(file).param("documentKey", "report")
                .header("Authorization", "Bearer test-token").header("X-Tenant-Id", TENANT).header("Idempotency-Key", "http"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.sha256").isNotEmpty());
        api.perform(multipart("/v1/workbench/documents").file(file).param("documentKey", "different")
                .header("Authorization", "Bearer test-token").header("X-Tenant-Id", TENANT).header("Idempotency-Key", "http"))
                .andExpect(status().isConflict());
        api.perform(multipart("/v1/workbench/documents").file(file).param("documentKey", "report")
                .header("Authorization", "Bearer test-token").header("X-Tenant-Id", UUID.randomUUID()).header("Idempotency-Key", "other"))
                .andExpect(status().isUnauthorized());
    }
}
