/* Checks historical review-result recovery when Flyway applies V16.
 * Builds V15 PostgreSQL rows and stored JdbcReviewRepository command responses.
 * Missing or ambiguous correction evidence must remain unbound.
 */
package com.basis.documents.adapters.persistence;

import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ReviewResultMigrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512L * 1024 * 1024).withNanoCPUs(1_000_000_000L));
    JdbcTemplate db;
    UUID tenant = UUID.randomUUID(), fact = UUID.randomUUID(), task = UUID.randomUUID(), decision = UUID.randomUUID();
    @BeforeEach void seedV15() {
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        db = new JdbcTemplate(dataSource);
        db.execute("drop schema public cascade"); db.execute("create schema public");
        Flyway.configure().dataSource(dataSource).target("15").load().migrate();
        db.update("insert into doc_tenant(id,name) values (?,?)", tenant, tenant.toString());
        insertFact(fact, null);
        db.update("insert into review_task(id,tenant_id,fact_id,reason,status,version) values (?,?,?,'review','CLOSED',1)", task, tenant, fact);
        db.update("insert into review_decision(id,tenant_id,task_id,fact_id,actor,action,reason,previous_status,new_status) values (?,?,?,?,'reviewer','CORRECT','checked','NEEDS_REVIEW','APPROVED')", decision, tenant, task, fact);
    }
    void insertFact(UUID id, UUID old) {
        db.update("insert into normalized_fact(id,tenant_id,fact_type,context,value,raw_value,unit,period_end,source_document,source_version,location,extraction_method,confidence,status,supersedes_fact_id) "
                + "values (?,?,'revenue','default',11,'11','MONETARY','2025-12-31','filing','old','page=1','OCR',700,'APPROVED',?)", id, tenant, old);
    }
    void response(UUID result) {
        String json = "{\"task\":{\"id\":\"" + task + "\"},\"fact\":{\"id\":\"" + result + "\"},\"action\":\"CORRECT\"}";
        db.update("insert into idempotency_key(tenant_id,key,response_json) values (?,?,?::jsonb)", tenant, UUID.randomUUID().toString(), json);
    }
    UUID migrateAndResult() {
        Flyway.configure().dataSource(db.getDataSource()).load().migrate();
        return db.queryForObject("select result_fact_id from review_decision where id=?", UUID.class, decision);
    }
    @Test void backfillsOnlyTheExactStoredCorrectionResult() {
        UUID result = UUID.randomUUID(); insertFact(result, fact); response(result);
        UUID unrelated = UUID.randomUUID(); insertFact(unrelated, fact);
        assertThat(migrateAndResult()).isEqualTo(result);
    }
    @Test void missingHistoricalResponseRemainsUnbound() {
        insertFact(UUID.randomUUID(), fact);
        assertThat(migrateAndResult()).isNull();
    }
    @Test void ambiguousHistoricalResponsesRemainUnbound() {
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        insertFact(first, fact); insertFact(second, fact); response(first); response(second);
        assertThat(migrateAndResult()).isNull();
    }
}
