package com.basis.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.basis.domain.Transaction;
import com.basis.ledger.LedgerState;
import com.basis.support.GeneratedHistory;
import com.basis.support.GeneratedHistory.Intent;
import com.basis.support.GeneratedHistory.Kind;
import com.basis.support.LedgerInvariants;
import com.basis.domain.LotSelectionMethod;
import java.math.BigDecimal;
import java.util.List;
import javax.sql.DataSource;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Invariant 7: truncate the derived tables, replay the posting table in id order, and the
 * derived state hash is byte identical.
 *
 * <p>This is the invariant that says derived state is genuinely derived. If it holds, the
 * position and lot tables can be thrown away and rebuilt at any time, which is what makes
 * a parser fix a replay rather than a migration. If it fails, some piece of state is
 * hiding in the derived tables and cannot be recovered from the ledger.
 *
 * <p>Also asserts the stronger statement that the projection from postings equals the
 * state the in memory ledger built while recording those same postings. A hash that
 * matches itself only proves the projector is repeatable; matching the ledger proves it is
 * right.
 *
 * <p>Runs without a Spring context. jqwik's lifecycle hooks own the container, so the
 * property is driven by jqwik alone rather than by two frameworks arguing over the same
 * static fields.
 */
@Label("invariant 7, replay determinism")
class ReplayDeterminismProperties {

    private static PostgreSQLContainer<?> postgres;
    private static JdbcClient db;
    private static LedgerRepository ledgerRepository;
    private static DerivedStateRepository derivedRepository;
    private static DerivedStateProjector projector;

    @BeforeContainer
    static void startDatabase() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        migrate(dataSource);

        db = JdbcClient.create(dataSource);
        ledgerRepository = new LedgerRepository(db);
        derivedRepository = new DerivedStateRepository(db);
        projector = new DerivedStateProjector(ledgerRepository, derivedRepository);
    }

    @AfterContainer
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static void migrate(DataSource dataSource) {
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @Property(tries = 15)
    @Label("replaying the posting table rebuilds byte identical derived state")
    void replayRebuildsIdenticalDerivedState(@ForAll("histories") List<Intent> intents) {
        clearEverything();
        GeneratedHistory history = GeneratedHistory.run(intents);
        persist(history);

        LedgerState firstPass = projector.rebuild();
        String firstHash = derivedRepository.hash();
        List<String> firstDump = derivedRepository.canonicalDump();

        derivedRepository.truncate();
        assertThat(derivedRepository.countPositions()).isZero();
        assertThat(derivedRepository.countLots()).isZero();
        assertThat(derivedRepository.countRealizedGains()).isZero();

        projector.rebuild();
        String secondHash = derivedRepository.hash();

        assertThat(secondHash)
                .as("invariant 7, derived state hash is byte identical after a truncate and replay.%nFirst pass:%n%s",
                        String.join(System.lineSeparator(), firstDump))
                .isEqualTo(firstHash);
        assertThat(derivedRepository.canonicalDump()).isEqualTo(firstDump);
        assertThat(firstPass.positions()).isEqualTo(projector.project().positions());
    }

    @Property(tries = 15)
    @Label("the projection from postings equals the state the ledger built while recording them")
    void projectionMatchesTheLiveLedger(@ForAll("histories") List<Intent> intents) {
        clearEverything();
        GeneratedHistory history = GeneratedHistory.run(intents);
        persist(history);

        LedgerState projected = projector.rebuild();

        assertThat(projected.positions())
                .as("projected positions equal the live ledger's")
                .isEqualTo(history.state().positions());
        assertThat(projected.allLots())
                .as("projected lots equal the live ledger's")
                .isEqualTo(history.state().allLots());
        assertThat(projected.realizedGains())
                .as("projected realized gains equal the live ledger's")
                .isEqualTo(history.state().realizedGains());

        LedgerInvariants.assertAllHold(projected, history.recorded());
        LedgerInvariants.assertCashIsConserved(projected, history.cashAccount(), history.expectedCash());
    }

    @Property(tries = 15)
    @Label("invariant 1 holds when asked of the database rather than of the application")
    void databaseAgreesThatEveryTransactionBalances(@ForAll("histories") List<Intent> intents) {
        clearEverything();
        GeneratedHistory history = GeneratedHistory.run(intents);
        persist(history);

        assertThat(ledgerRepository.findUnbalancedTransactions())
                .as("SUM(weight_minor) per transaction and currency is zero, straight from SQL")
                .isEmpty();
    }

    @Property(tries = 10)
    @Label("re appending the same transactions is a no op, enforced by the unique idempotency key")
    void reimportingIsANoOp(@ForAll("histories") List<Intent> intents) {
        clearEverything();
        GeneratedHistory history = GeneratedHistory.run(intents);
        long firstBatch = persist(history);
        int afterFirst = ledgerRepository.countTransactions();
        String hashAfterFirst = hashOf();

        long secondBatch = new ImportBatchRepository(db).open("test", "replay.csv", new byte[] {2});
        long written = history.recorded().stream()
                .filter(txn -> ledgerRepository.append(secondBatch, txn))
                .count();

        assertThat(written).as("nothing was written the second time").isZero();
        assertThat(ledgerRepository.countTransactions()).isEqualTo(afterFirst);
        assertThat(hashOf()).isEqualTo(hashAfterFirst);
        assertThat(firstBatch).isNotEqualTo(secondBatch);
    }

    private static String hashOf() {
        projector.rebuild();
        return derivedRepository.hash();
    }

    private long persist(GeneratedHistory history) {
        ImportBatchRepository batches = new ImportBatchRepository(db);
        long batchId = batches.open("test", "generated.csv", new byte[] {1});
        for (Transaction txn : history.recorded()) {
            ledgerRepository.append(batchId, txn);
        }
        batches.commit(batchId, history.recorded().size());
        return batchId;
    }

    private void clearEverything() {
        db.sql("DELETE FROM import_batch").update();
        derivedRepository.truncate();
    }

    @Provide
    Arbitrary<List<Intent>> histories() {
        Arbitrary<Intent> intent = Combinators.combine(
                        Arbitraries.of(Kind.values()),
                        Arbitraries.integers().between(0, GeneratedHistory.COMMODITIES.size() - 1),
                        Arbitraries.oneOf(
                                Arbitraries.integers().between(1, 500).map(BigDecimal::valueOf),
                                Arbitraries.bigDecimals()
                                        .between(new BigDecimal("0.00000001"), new BigDecimal("500"))
                                        .ofScale(8)),
                        Arbitraries.bigDecimals()
                                .between(new BigDecimal("0.000001"), new BigDecimal("5000"))
                                .ofScale(6),
                        Arbitraries.bigDecimals()
                                .between(BigDecimal.ZERO, new BigDecimal("19.99"))
                                .ofScale(2),
                        Arbitraries.integers().between(1, 100),
                        Arbitraries.of(
                                LotSelectionMethod.FIFO,
                                LotSelectionMethod.LIFO,
                                LotSelectionMethod.HIFO,
                                LotSelectionMethod.SPECIFIC_LOT))
                .as(Intent::new);
        return intent.list().ofMinSize(1).ofMaxSize(12);
    }
}
