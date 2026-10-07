package com.basis.documents.adapters.persistence;

import static org.assertj.core.api.Assertions.*;

import com.basis.documents.application.*;
import com.basis.documents.domain.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class DocumentJobPersistenceTest {
    @Container @ServiceConnection static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired JdbcTemplate db;
    @Autowired ProcessingJobRepository jobs;
    private final UUID tenant = UUID.randomUUID(), document = UUID.randomUUID(), version = UUID.randomUUID(), job = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(10);

    @BeforeEach void seed() {
        db.execute("truncate doc_tenant cascade");
        db.update("insert into doc_tenant(id,name) values (?,?)", tenant, "test");
        db.update("insert into document(id,tenant_id,document_key) values (?,?,?)", document,tenant,"fixture");
        db.update("insert into document_version(id,tenant_id,document_id,sha256,filename,media_type,size_bytes) values (?,?,?,'hash','fixture.csv','text/csv',10)",version,tenant,document);
        db.update("insert into processing_job(id,tenant_id,version_id,idempotency_key,correlation_id) values (?,?,?,'fixture','correlation')",job,tenant,version);
    }

    @Test void onlyOneCompetingWorkerClaimsTheJob() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> run = () -> { start.await(); return jobs.claim(tenant.toString(), Thread.currentThread().getName(), NOW, TTL).isPresent(); };
            var a=pool.submit(run); var b=pool.submit(run); start.countDown();
            assertThat((a.get(10,TimeUnit.SECONDS)?1:0)+(b.get(10,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
        }
    }

    @Test void expiredLeaseAndReusedOwnerCannotCommit() {
        var old = jobs.claim(tenant.toString(), "worker", NOW, TTL).orElseThrow();
        assertThat(jobs.checkpoint(old,PipelineStage.CLASSIFY,"{}",NOW)).isTrue();
        assertThat(jobs.checkpoint(old,PipelineStage.LAYOUT,"{}",NOW.plusSeconds(10))).isFalse();
        var replacement=jobs.claim(tenant.toString(),"worker",NOW.plusSeconds(10),TTL).orElseThrow();
        assertThat(replacement.job().checkpoint()).isEqualTo(PipelineStage.CLASSIFY);
        assertThat(jobs.checkpoint(old,PipelineStage.LAYOUT,"{}",NOW.plusSeconds(11))).isFalse();
        assertThat(jobs.checkpoint(replacement,PipelineStage.LAYOUT,"{}",NOW.plusSeconds(11))).isTrue();
    }

    @Test void invalidArtifactRollsBackCheckpointAndOutbox() {
        var lease=jobs.claim(tenant.toString(),"worker",NOW,TTL).orElseThrow();
        assertThatThrownBy(() -> jobs.checkpoint(lease,PipelineStage.CLASSIFY,"not-json",NOW)).isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("select checkpoint from processing_job where id=?",String.class,job)).isNull();
        assertThat(db.queryForObject("select count(*) from outbox_event",Integer.class)).isZero();
        assertThat(jobs.checkpoint(lease,PipelineStage.CLASSIFY,"{}",NOW)).isTrue();
    }

    @Test void restartAtEveryStageReusesCommittedOutputAndFinishes() {
        for (PipelineStage stage:PipelineStage.values()) {
            var lease=jobs.claim(tenant.toString(),"worker",NOW.plusSeconds(stage.ordinal()*11L),TTL).orElseThrow();
            assertThat(jobs.checkpoint(lease,stage,"{\"fixture\":true}",NOW.plusSeconds(stage.ordinal()*11L))).isTrue();
        }
        var resumed=jobs.claim(tenant.toString(),"restarted",NOW.plusSeconds(90),TTL).orElseThrow();
        assertThat(jobs.complete(resumed,NOW.plusSeconds(90))).isTrue();
        assertThat(db.queryForObject("select count(*) from document_stage_artifact",Integer.class)).isEqualTo(PipelineStage.values().length);
        assertThat(jobs.artifacts(tenant.toString(), job.toString())).hasSize(PipelineStage.values().length);
        assertThat(jobs.artifacts(tenant.toString(), job.toString()).get(PipelineStage.CLASSIFY)).contains("fixture");
        assertThat(jobs.artifacts(UUID.randomUUID().toString(), job.toString())).isEmpty();
        assertThat(db.queryForObject("select count(*) from outbox_event",Integer.class)).isEqualTo(PipelineStage.values().length);
    }

    @Test void cancellationRetryAndBackoffAreDurable() {
        var old=jobs.claim(tenant.toString(),"worker",NOW,TTL).orElseThrow();
        assertThat(jobs.cancel(tenant.toString(),job.toString())).isTrue();
        assertThat(jobs.retry(tenant.toString(),job.toString())).isTrue();
        var replacement=jobs.claim(tenant.toString(),"worker",NOW,TTL).orElseThrow();
        assertThat(jobs.checkpoint(old,PipelineStage.CLASSIFY,"{}",NOW)).isFalse();
        assertThat(jobs.fail(replacement,"timeout",JobStatus.QUEUED,NOW.plusSeconds(5),NOW)).isTrue();
        assertThat(jobs.claim(tenant.toString(),"worker",NOW,TTL)).isEmpty();
        assertThat(jobs.claim(UUID.randomUUID().toString(),"worker",NOW.plusSeconds(5),TTL)).isEmpty();
        assertThat(jobs.claim(tenant.toString(),"worker",NOW.plusSeconds(5),TTL)).isPresent();
    }

    @Test void workerResumesAfterCheckpointAndCreatesAllArtifacts() {
        var lease=jobs.claim(tenant.toString(),"crashed",NOW,TTL).orElseThrow();
        jobs.checkpoint(lease,PipelineStage.CLASSIFY,"{}",NOW);
        AtomicInteger calls=new AtomicInteger();
        var stages=new EnumMap<PipelineStage,DurablePipelineWorker.Stage>(PipelineStage.class);
        for(var stage:PipelineStage.values()) stages.put(stage,l -> { calls.incrementAndGet(); return "{}"; });
        var worker=new DurablePipelineWorker(jobs,"replacement",TTL,3,Clock.fixed(NOW.plusSeconds(11),ZoneOffset.UTC));
        assertThat(worker.runOnce(tenant.toString(),stages)).isTrue();
        assertThat(calls.get()).isEqualTo(7);
        assertThat(db.queryForObject("select status from processing_job where id=?",String.class,job)).isEqualTo("COMPLETED");
    }
}
