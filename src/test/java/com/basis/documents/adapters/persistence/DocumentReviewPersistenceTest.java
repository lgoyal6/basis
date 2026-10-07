package com.basis.documents.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basis.documents.application.ReviewRepository;
import com.basis.documents.application.ReviewWorkflow;
import com.basis.documents.domain.ExtractionMethod;
import com.basis.documents.domain.FactStatus;
import com.basis.documents.domain.FactUnit;
import com.basis.documents.domain.NormalizedFact;
import com.basis.documents.domain.ReviewAction;
import com.basis.documents.domain.ReviewTask;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class DocumentReviewPersistenceTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate db;
    @Autowired ReviewRepository reviews;

    private final UUID tenant = UUID.randomUUID();
    private final UUID fact = UUID.randomUUID();
    private final UUID task = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        db.update("delete from review_decision");
        db.update("delete from outbox_event");
        db.update("delete from idempotency_key");
        db.update("delete from review_task");
        db.update("delete from normalized_fact");
        db.update("delete from doc_tenant");
        db.update("insert into doc_tenant (id, name) values (?, ?)", tenant, "test-" + tenant);
        db.update("insert into normalized_fact (id, tenant_id, issuer, fact_type, context, value, raw_value, unit, currency, period_start, period_end, source_document, source_version, location, extraction_method, confidence, status) "
                + "values (?, ?, 'ACME', 'revenue', 'consolidated', 10, '10', 'MONETARY', 'USD', ?, ?, 'filing.csv', 'sha', 'row=1', 'TEXT', 800, 'NEEDS_REVIEW')",
                fact, tenant, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
        db.update("insert into review_task (id, tenant_id, fact_id, reason) values (?, ?, ?, 'low confidence')", task, tenant, fact);
    }

    @Test
    void correctionIsAtomicIdempotentAndPublished() {
        ReviewTask reviewTask = reviews.findTask(tenant.toString(), task.toString());
        NormalizedFact original = reviews.findFact(tenant.toString(), fact.toString());
        var decision = new ReviewWorkflow().decide(reviewTask, original, 0, ReviewAction.CORRECT, "ignored", "checked footnote", new BigDecimal("11"));

        decision = reviews.decide(tenant.toString(), task.toString(), "review-1", new ReviewWorkflow.Command(0, ReviewAction.CORRECT, "reviewer", "checked footnote", new BigDecimal("11")));

        var replay = reviews.decide(tenant.toString(), task.toString(), "review-1", new ReviewWorkflow.Command(0, ReviewAction.CORRECT, "reviewer", "checked footnote", new BigDecimal("11")));
        assertThat(replay).isEqualTo(decision);
        assertThatThrownBy(() -> reviews.decide(tenant.toString(), task.toString(), "review-1", new ReviewWorkflow.Command(0, ReviewAction.REJECT, "reviewer", "different", null))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> reviews.decide(UUID.randomUUID().toString(), task.toString(), "other", new ReviewWorkflow.Command(0, ReviewAction.APPROVE, "reviewer", "cross tenant", null))).isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("select status from normalized_fact where id = ?", String.class, fact)).isEqualTo("SUPERSEDED");
        assertThat(db.queryForObject("select count(*) from normalized_fact where tenant_id = ?", Integer.class, tenant)).isEqualTo(2);
        assertThat(db.queryForObject("select count(*) from review_decision where tenant_id = ?", Integer.class, tenant)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from outbox_event where tenant_id = ? and event_type = 'review.decision.recorded'", Integer.class, tenant)).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from idempotency_key where tenant_id = ? and key = 'review-1'", Integer.class, tenant)).isEqualTo(1);
    }
}
