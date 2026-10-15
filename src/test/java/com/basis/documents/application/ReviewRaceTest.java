/* Checks review, source-version and export races against PostgreSQL.
 * Exercises JdbcReviewRepository, current-source pointers and the CSV exporter.
 * Valid approvals succeed; stale, duplicate and unreviewed exports must fail.
 */
package com.basis.documents.application;

import static org.assertj.core.api.Assertions.*;

import com.basis.documents.domain.ReviewAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class ReviewRaceTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024).withNanoCPUs(1_000_000_000L));
    @Autowired JdbcTemplate db;
    @Autowired ReviewRepository reviews;
    @Autowired ApprovedFactExportService exports;
    @Autowired PlatformTransactionManager transactions;
    private final UUID tenant = UUID.randomUUID(), document = UUID.randomUUID(), version = UUID.randomUUID();
    private final UUID fact = UUID.randomUUID(), task = UUID.randomUUID();

    @BeforeEach void seed() {
        db.execute("truncate doc_tenant cascade");
        db.update("insert into doc_tenant(id,name) values (?,?)", tenant, "race");
        db.update("insert into document(id,tenant_id,document_key) values (?,?, 'filing')", document, tenant);
        db.update("insert into document_version(id,tenant_id,document_id,sha256,filename,media_type,size_bytes) values (?,?,?,'old','f.csv','text/csv',10)", version, tenant, document);
        db.update("update document set current_version_id=? where id=?", version, document);
        db.update("insert into normalized_fact(id,tenant_id,fact_type,context,value,raw_value,unit,period_end,source_document,source_version,location,extraction_method,confidence,status) "
                + "values (?,?,'revenue','default',10,'10','MONETARY','2025-12-31','filing','old','page=1','OCR',700,'NEEDS_REVIEW')", fact, tenant);
        db.update("insert into review_task(id,tenant_id,fact_id,reason) values (?,?,?,'OCR')", task, tenant, fact);
    }
    private ReviewWorkflow.Command command(ReviewAction action) {
        return new ReviewWorkflow.Command(0, action, "reviewer", "source checked", action == ReviewAction.CORRECT ? new java.math.BigDecimal("11") : null);
    }
    private ReviewWorkflow.Decision approve(String key) { return reviews.decide(tenant.toString(), task.toString(), key, command(ReviewAction.APPROVE)); }
    private String export(UUID id) { return new String(exports.export(id.toString()), StandardCharsets.UTF_8); }
    private void newSource() {
        UUID next = UUID.randomUUID();
        db.update("insert into document_version(id,tenant_id,document_id,sha256,filename,media_type,size_bytes) values (?,?,?,'new','f.csv','text/csv',11)", next, tenant, document);
        db.update("update document set current_version_id=? where id=?", next, document);
    }
    @Test void competingApprovalsProduceOneDecisionAndOneOutbox() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Boolean> a = () -> { start.await(); try { approve(Thread.currentThread().getName()); return true; } catch (IllegalStateException e) { return false; } };
            var left = pool.submit(a); var right = pool.submit(a); start.countDown();
            assertThat((left.get(15, TimeUnit.SECONDS) ? 1 : 0) + (right.get(15, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
        }
        assertThat(db.queryForObject("select count(*) from review_decision", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from outbox_event", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from idempotency_key", Integer.class)).isEqualTo(1);
        assertThat(export(tenant)).contains(fact.toString(), "review_decision_id", "old");
    }
    @Test void newVersionWinsRaceAndStaleApprovalRollsBack() throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            var launched = new CountDownLatch(1);
            Future<?>[] pending = new Future<?>[1];
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                db.queryForObject("select id from doc_tenant where id=? for update", UUID.class, tenant);
                pending[0] = pool.submit(() -> { launched.countDown(); return approve("racing"); });
                try { assertThat(launched.await(5, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                newSource();
            });
            assertThatThrownBy(() -> pending[0].get(15, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
        assertThat(db.queryForObject("select count(*) from review_decision", Integer.class)).isZero();
        assertThat(db.queryForObject("select count(*) from idempotency_key", Integer.class)).isZero();
        assertThat(export(tenant)).doesNotContain(fact.toString());
    }
    @Test void approvalBeforeNewVersionIsAuditableButNoLongerExportable() {
        approve("first");
        assertThat(export(tenant)).contains(fact.toString());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            db.queryForObject("select id from doc_tenant where id=? for update", UUID.class, tenant);
            newSource();
        });
        assertThat(export(tenant)).doesNotContain(fact.toString());
        assertThat(db.queryForObject("select count(*) from review_decision", Integer.class)).isEqualTo(1);
    }
    @Test void exportRejectsUnreviewedStatusAndCrossTenantWhileCorrectionHasDecision() {
        assertThat(export(tenant)).doesNotContain(fact.toString());
        db.update("update normalized_fact set status='APPROVED' where id=?", fact);
        assertThat(export(tenant)).doesNotContain(fact.toString());
        db.update("update normalized_fact set status='NEEDS_REVIEW' where id=?", fact);
        var decision = reviews.decide(tenant.toString(), task.toString(), "correct", command(ReviewAction.CORRECT));
        assertThat(export(tenant)).contains(decision.fact().id()).doesNotContain('"' + fact.toString() + '"');
        assertThat(db.queryForObject("select result_fact_id from review_decision where tenant_id=?", UUID.class, tenant)).isEqualTo(UUID.fromString(decision.fact().id()));
        UUID unreviewedSuccessor = UUID.randomUUID();
        db.update("insert into normalized_fact(id,tenant_id,fact_type,context,value,raw_value,unit,period_end,source_document,source_version,location,extraction_method,confidence,status,supersedes_fact_id) "
                + "values (?,?,'revenue','default',99,'99','MONETARY','2025-12-31','filing','old','page=1','OCR',700,'APPROVED',?)", unreviewedSuccessor, tenant, fact);
        assertThat(export(tenant)).contains(decision.fact().id()).doesNotContain(unreviewedSuccessor.toString());
        assertThat(export(UUID.randomUUID())).doesNotContain(decision.fact().id());
        assertThatThrownBy(() -> reviews.decide(UUID.randomUUID().toString(), task.toString(), "other", command(ReviewAction.APPROVE))).isInstanceOf(RuntimeException.class);
    }
    @Test void outboxFailureRollsBackApprovalAndRetrySucceeds() {
        db.execute("alter table outbox_event add constraint reject_review check(event_type <> 'review.decision.recorded')");
        try { assertThatThrownBy(() -> approve("retry")).isInstanceOf(RuntimeException.class); }
        finally { db.execute("alter table outbox_event drop constraint reject_review"); }
        assertThat(db.queryForObject("select status from normalized_fact where id=?", String.class, fact)).isEqualTo("NEEDS_REVIEW");
        assertThat(db.queryForObject("select count(*) from review_decision", Integer.class)).isZero();
        assertThat(db.queryForObject("select count(*) from idempotency_key", Integer.class)).isZero();
        approve("retry");
        assertThat(export(tenant)).contains(fact.toString());
    }
    @Test void registeredDocumentRejectsUnknownSourceVersionAndMissingCurrentPointer() {
        db.update("update normalized_fact set source_version='unknown' where id=?", fact);
        assertThatThrownBy(() -> approve("unknown")).isInstanceOf(IllegalStateException.class).hasMessageContaining("source version");
        db.update("update normalized_fact set source_version='old' where id=?", fact);
        approve("valid");
        assertThat(export(tenant)).contains(fact.toString());
        db.update("update document set current_version_id=null where id=?", document);
        assertThat(export(tenant)).doesNotContain(fact.toString());
    }
}
