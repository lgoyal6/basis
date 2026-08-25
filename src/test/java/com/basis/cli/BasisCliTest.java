package com.basis.cli;

import static com.basis.support.Fixtures.AAPL;
import static com.basis.support.Fixtures.IBKR;
import static com.basis.support.Fixtures.JAN_15;
import static com.basis.support.Fixtures.buy;
import static org.assertj.core.api.Assertions.assertThat;

import com.basis.domain.Transaction;
import com.basis.ledger.Ledger;
import com.basis.persistence.BreakRecordRepository;
import com.basis.persistence.ImportBatchRepository;
import com.basis.persistence.LedgerRepository;
import com.basis.persistence.ReferenceDataRepository;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The commands, against a real database.
 *
 * <p>Exercised through the same entry point the shell uses, so what these tests assert is
 * what a person actually sees. The output is the product for a tool like this: a break
 * nobody can read is a break nobody acts on.
 */
@SpringBootTest
@Testcontainers
class BasisCliTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JdbcClient db;

    @Autowired
    LedgerRepository ledgerRepository;

    @Autowired
    ImportBatchRepository batches;

    @Autowired
    BreakRecordRepository breaks;

    @Autowired
    ReferenceDataRepository referenceData;

    @Autowired
    com.basis.persistence.DerivedStateProjector projector;

    @Autowired
    com.basis.persistence.DerivedStateRepository derived;

    @Autowired
    com.basis.reference.SplitRefreshService refresh;

    @Autowired
    com.basis.persistence.StartupRecovery recovery;

    @Autowired
    com.basis.importer.ImportService importer;

    private ByteArrayOutputStream stdout;
    private BasisCli cli;

    @BeforeEach
    void reset() {
        db.sql("DELETE FROM import_batch").update();
        db.sql("DELETE FROM break_record").update();
        db.sql("DELETE FROM reference_data").update();
        db.sql("DELETE FROM reference_data_fetch").update();
        derived.truncate();

        stdout = new ByteArrayOutputStream();
        CliOutput out = new CliOutput(
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stdout, true, StandardCharsets.UTF_8));
        cli = new BasisCli(projector, derived, breaks, referenceData, refresh, recovery, importer, out);
    }

    private int run(String... args) {
        cli.run(new DefaultApplicationArguments(args));
        return cli.getExitCode();
    }

    private String printed() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("no arguments prints usage and exits 2")
    void noArgumentsIsAUsageError() {
        assertThat(run()).isEqualTo(BasisCli.USAGE);
        assertThat(printed()).contains("reconcile").contains("exit codes");
    }

    @Test
    @DisplayName("an unknown command says so rather than doing nothing")
    void unknownCommandIsLoud() {
        assertThat(run("frobnicate")).isEqualTo(BasisCli.USAGE);
        assertThat(printed()).contains("unknown command 'frobnicate'");
    }

    @Test
    @DisplayName("status on an empty ledger says it is empty rather than printing nothing")
    void statusOnAnEmptyLedger() {
        assertThat(run("status")).isEqualTo(BasisCli.OK);
        assertThat(printed())
                .contains("HOLDINGS")
                .contains("Nothing has been imported yet")
                .contains("0 open")
                .contains("0 symbols with split history");
    }

    @Test
    @DisplayName("reconcile finds the unapplied split, records it, and exits 3")
    void reconcileReportsBreaksAndExitsThree(@TempDir Path dir) throws Exception {
        givenAHistoryOf(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        referenceData.cacheSplit("AAPL", JAN_15.plusMonths(1), 4, 1, "test");
        Path positions = write(dir, "positions.csv", """
                symbol,quantity,cost_basis,kind
                AAPL,40,,EQUITY
                """);

        int code = run("reconcile", IBKR.name(), positions.toString(), "--as-of=2026-03-31");

        assertThat(code)
                .as("breaks are their own outcome, not a failure, so a pipeline can act on them")
                .isEqualTo(BasisCli.BREAKS_FOUND);
        assertThat(printed())
                .contains("QUANTITY_MISMATCH")
                .contains("4 for 1")
                .contains("next: Apply the 4 for 1 split")
                .contains("1 break(s) recorded");
        assertThat(breaks.countOpen()).isEqualTo(1);
    }

    @Test
    @DisplayName("a dry run shows the breaks without writing any")
    void dryRunRecordsNothing(@TempDir Path dir) throws Exception {
        givenAHistoryOf(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        Path positions = write(dir, "positions.csv", "AAPL,40\n");

        assertThat(run("reconcile", IBKR.name(), positions.toString(), "--as-of=2026-03-31", "--dry-run"))
                .isEqualTo(BasisCli.BREAKS_FOUND);
        assertThat(printed()).contains("not recorded (--dry-run)");
        assertThat(breaks.countOpen()).isZero();
    }

    @Test
    @DisplayName("agreement exits 0 and says so")
    void agreementExitsZero(@TempDir Path dir) throws Exception {
        givenAHistoryOf(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        Path positions = write(dir, "positions.csv", "AAPL,10\n");

        assertThat(run("reconcile", IBKR.name(), positions.toString(), "--as-of=2026-03-31"))
                .isEqualTo(BasisCli.OK);
        assertThat(printed()).contains("no breaks");
    }

    @Test
    @DisplayName("reconcile without --as-of is refused rather than assuming today")
    void asOfIsRequired(@TempDir Path dir) throws Exception {
        Path positions = write(dir, "positions.csv", "AAPL,40\n");

        assertThat(run("reconcile", IBKR.name(), positions.toString())).isEqualTo(BasisCli.USAGE);
        assertThat(printed()).contains("--as-of");
    }

    @Test
    @DisplayName("a cash row needs the snapshot to declare that it covers cash")
    void cashNeedsToBeDeclared(@TempDir Path dir) throws Exception {
        givenAHistoryOf(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        Path positions = write(dir, "positions.csv", "AAPL,10\nUSD,500.00,,CURRENCY\n");

        assertThat(run("reconcile", IBKR.name(), positions.toString(), "--as-of=2026-03-31"))
                .isEqualTo(BasisCli.USAGE);
        assertThat(printed()).contains("--with-cash");

        assertThat(run("reconcile", IBKR.name(), positions.toString(), "--as-of=2026-03-31", "--with-cash"))
                .isEqualTo(BasisCli.BREAKS_FOUND);
    }

    @Test
    @DisplayName("breaks lists what is open, with the cause and what to do")
    void breaksListsOpenOnes(@TempDir Path dir) throws Exception {
        givenAHistoryOf(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        Path positions = write(dir, "positions.csv", "AAPL,40\n");
        run("reconcile", IBKR.name(), positions.toString(), "--as-of=2026-03-31");
        reset0();

        assertThat(run("breaks", IBKR.name())).isEqualTo(BasisCli.BREAKS_FOUND);
        assertThat(printed())
                .contains("UNAPPLIED_SPLIT (suspected)")
                .contains("next: Refresh the reference data")
                .contains("1 open break(s)");
    }

    @Test
    @DisplayName("settle records a judgement and closes the break")
    void settleClosesABreak(@TempDir Path dir) throws Exception {
        givenAHistoryOf(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        Path positions = write(dir, "positions.csv", "AAPL,40\n");
        run("reconcile", IBKR.name(), positions.toString(), "--as-of=2026-03-31");
        long id = db.sql("SELECT id FROM break_record ORDER BY id LIMIT 1").query(Long.class).single();
        reset0();

        assertThat(run("settle", String.valueOf(id), "--accept", "--note=applied the split"))
                .isEqualTo(BasisCli.OK);
        assertThat(printed()).contains("settled as ACCEPTED");
        assertThat(breaks.countOpen()).isZero();
    }

    @Test
    @DisplayName("settle needs exactly one verdict, not none and not several")
    void settleNeedsOneVerdict() {
        assertThat(run("settle", "1")).isEqualTo(BasisCli.USAGE);
        assertThat(printed()).contains("exactly one of --accept");

        reset0();
        assertThat(run("settle", "1", "--accept", "--reject", "--resolved"))
                .as("all three is not one")
                .isEqualTo(BasisCli.USAGE);
    }

    @Test
    @DisplayName("rebuild replays derived state and prints a hash of it")
    void rebuildReplaysAndHashes() {
        givenAHistoryOf(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));

        assertThat(run("rebuild")).isEqualTo(BasisCli.OK);
        assertThat(printed()).contains("rebuilt: 2 positions, 1 lots").contains("hash: ");
    }

    @Test
    @DisplayName("import reads a Fidelity statement, and re importing it changes nothing")
    void importsAFidelityStatement(@TempDir Path dir) throws Exception {
        Path statement = write(dir, "history.csv", """
                Run Date,Action,Symbol,Description,Quantity,Price ($),Commission ($),Amount ($)
                01/05/2026,ELECTRONIC FUNDS TRANSFER RECEIVED,,CONTRIBUTION,,,,10000.00
                01/15/2026,YOU BOUGHT,AAPL,"APPLE INC, COM",10,150.00,1.00,-1501.00
                """);

        assertThat(run("import", "fidelity", "Assets:Broker:Fidelity", statement.toString()))
                .isEqualTo(BasisCli.OK);
        assertThat(printed()).contains("2 row(s) read, 2 transaction(s) recorded");

        reset0();
        assertThat(run("import", "fidelity", "Assets:Broker:Fidelity", statement.toString()))
                .isEqualTo(BasisCli.OK);
        assertThat(printed())
                .as("overlapping statements are the normal way to use this")
                .contains("0 transaction(s) recorded, 2 already present");
    }

    @Test
    @DisplayName("an unknown broker is refused, and says which ones exist")
    void refusesAnUnknownBroker(@TempDir Path dir) throws Exception {
        Path statement = write(dir, "history.csv", "anything\n");

        assertThat(run("import", "etrade", "Assets:Broker:Etrade", statement.toString()))
                .isEqualTo(BasisCli.USAGE);
        assertThat(printed())
                .contains("no broker profile")
                .contains("Available: fidelity")
                .contains("not code");
    }

    @Test
    @DisplayName("recover reports that nothing was interrupted")
    void recoverOnACleanDatabase() {
        assertThat(run("recover")).isEqualTo(BasisCli.OK);
        assertThat(printed()).contains("nothing was in flight");
    }

    @Test
    @DisplayName("recover rolls back an interrupted import and says how many")
    void recoverRollsBackAnInterruptedBatch() {
        Ledger ledger = new Ledger();
        long batch = batches.open("ibkr", "half-written.csv", new byte[] {1});
        ledgerRepository.append(batch, ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00")));

        assertThat(run("recover")).isEqualTo(BasisCli.OK);
        assertThat(printed()).contains("rolled back 1 interrupted import batch(es)");
    }

    @Test
    @DisplayName("refresh-splits is a no op while the fetcher is switched off")
    void refreshIsOffByDefault() {
        assertThat(run("refresh-splits", "AAPL")).isEqualTo(BasisCli.OK);
        assertThat(printed()).contains("0 attempted");
    }

    /** Clears only the captured output, keeping the database, for multi step scenarios. */
    private void reset0() {
        stdout.reset();
    }

    private void givenAHistoryOf(com.basis.domain.event.LedgerEvent... events) {
        Ledger ledger = new Ledger();
        long batch = batches.open("test", "history.csv", new byte[] {1});
        for (com.basis.domain.event.LedgerEvent event : events) {
            Transaction txn = ledger.record(event);
            ledgerRepository.append(batch, txn);
        }
        batches.commit(batch, events.length);
        projector.rebuild();
    }

    private static Path write(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
