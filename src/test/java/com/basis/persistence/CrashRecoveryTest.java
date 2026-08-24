package com.basis.persistence;

import static com.basis.support.Fixtures.AAPL;
import static com.basis.support.Fixtures.FEB_01;
import static com.basis.support.Fixtures.JAN_15;
import static com.basis.support.Fixtures.buy;
import static com.basis.support.Fixtures.sell;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basis.domain.LotSelectionMethod;
import com.basis.domain.Transaction;
import com.basis.ledger.Ledger;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The crash marker, end to end, against a real Postgres.
 *
 * <p>A batch with a null {@code committed_at} is the only way the schema can say "this
 * import was interrupted", and the only acceptable outcomes on startup are rolled back or
 * resumed. This test is what stops that from silently becoming a third outcome where a
 * half imported statement looks fully imported, which is how a reconciliation tool ends up
 * reporting a break it caused itself.
 *
 * <p>Runs with a Spring context, unlike the replay property, because rollback correctness
 * depends on the {@code @Transactional} boundaries actually being applied.
 */
@SpringBootTest
@Testcontainers
class CrashRecoveryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JdbcClient db;

    @Autowired
    ImportBatchRepository batches;

    @Autowired
    LedgerRepository ledger;

    @Autowired
    DerivedStateRepository derived;

    @Autowired
    DerivedStateProjector projector;

    @Autowired
    StartupRecovery recovery;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM import_batch").update();
        derived.truncate();
    }

    @Test
    @DisplayName("flyway applied every migration")
    void migrationsApplied() {
        List<String> applied = db.sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class)
                .list();

        assertThat(applied).containsExactly("1", "2", "3", "4", "5", "6");
    }

    @Test
    @DisplayName("an open batch has a null committed_at and is found as in flight")
    void anOpenBatchIsInFlight() {
        long batchId = batches.open("ibkr", "statement.csv", new byte[] {1});

        assertThat(batches.isCommitted(batchId)).isFalse();
        assertThat(batches.findInFlight()).containsExactly(batchId);
    }

    @Test
    @DisplayName("a committed batch is no longer a recovery candidate")
    void aCommittedBatchIsNotARecoveryCandidate() {
        long batchId = batches.open("ibkr", "statement.csv", new byte[] {1});
        batches.commit(batchId, 3);

        assertThat(batches.isCommitted(batchId)).isTrue();
        assertThat(batches.findInFlight()).isEmpty();
    }

    @Test
    @DisplayName("startup rolls back an in flight batch and discards its transactions")
    void startupRollsBackAnInterruptedBatch() {
        long committed = writeBatch(true);
        long interrupted = writeBatch(false);

        assertThat(batches.countTransactions(interrupted)).isEqualTo(2);
        assertThat(batches.findInFlight()).containsExactly(interrupted);

        List<Long> rolledBack = recovery.recover();

        assertThat(rolledBack).containsExactly(interrupted);
        assertThat(batches.countTransactions(interrupted)).isZero();
        assertThat(batches.countTransactions(committed))
                .as("the committed batch is untouched")
                .isEqualTo(2);
        assertThat(batches.findInFlight())
                .as("no batch is left ambiguous")
                .isEmpty();
    }

    @Test
    @DisplayName("a rolled back batch leaves a trail rather than vanishing")
    void aRolledBackBatchLeavesATrail() {
        long interrupted = writeBatch(false);

        recovery.recover();

        String reason = db.sql("SELECT abandon_reason FROM import_batch WHERE id = :id")
                .param("id", interrupted)
                .query(String.class)
                .single();
        assertThat(reason).isEqualTo(StartupRecovery.REASON);
    }

    @Test
    @DisplayName("rolling back rebuilds derived state so nothing survives from the discarded rows")
    void rollbackRebuildsDerivedState() {
        writeBatch(true);
        projector.rebuild();
        int positionsBefore = derived.countPositions();
        int lotsBefore = derived.countLots();

        writeBatch(false);
        projector.rebuild();
        assertThat(derived.countLots())
                .as("the interrupted batch's lots are visible before recovery runs")
                .isGreaterThan(lotsBefore);

        recovery.recover();

        assertThat(derived.countPositions()).isEqualTo(positionsBefore);
        assertThat(derived.countLots()).isEqualTo(lotsBefore);
    }

    @Test
    @DisplayName("recovery is a no op when nothing was in flight")
    void recoveryIsANoOpWhenNothingWasInterrupted() {
        writeBatch(true);

        assertThat(recovery.recover()).isEmpty();
    }

    @Test
    @DisplayName("a batch cannot be committed twice, so a double commit cannot hide a crash")
    void aBatchCannotBeCommittedTwice() {
        long batchId = batches.open("ibkr", "statement.csv", new byte[] {1});
        batches.commit(batchId, 1);

        assertThatThrownBy(() -> batches.commit(batchId, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in flight");
    }

    @Test
    @DisplayName("every posting reads back with the weight it was written with")
    void storedWeightsAgreeWithTheCode() {
        writeBatch(true);

        List<PostingRow> rows = ledger.readAllInReplayOrder();

        assertThat(rows).isNotEmpty();
        assertThat(rows).allSatisfy(row -> assertThat(row.weightAgreesWithCode())
                .as("posting %d stored weight %d agrees with Posting.weight()", row.id(), row.storedWeightMinor())
                .isTrue());
        assertThat(rows).isSortedAccordingTo((left, right) -> Long.compare(left.id(), right.id()));
    }

    /** Writes a two transaction batch, committed or left in flight. */
    private long writeBatch(boolean commit) {
        Ledger inMemory = new Ledger();
        String suffix = commit ? "-committed" : "-interrupted";
        List<Transaction> transactions = List.of(
                inMemory.record(buy(JAN_15, "b1" + suffix, AAPL, "10", "150.00", "1.00")),
                inMemory.record(sell(FEB_01, "s1" + suffix, AAPL, "4", "160.00", "1.00",
                        LotSelectionMethod.FIFO)));

        long batchId = batches.open("ibkr", "statement" + suffix + ".csv", new byte[] {commit ? (byte) 1 : (byte) 2});
        for (Transaction txn : transactions) {
            ledger.append(batchId, txn);
        }
        if (commit) {
            batches.commit(batchId, transactions.size());
        }
        return batchId;
    }
}
