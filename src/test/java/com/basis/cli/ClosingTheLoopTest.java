package com.basis.cli;

import static com.basis.support.Fixtures.AAPL;
import static com.basis.support.Fixtures.IBKR;
import static com.basis.support.Fixtures.JAN_15;
import static com.basis.support.Fixtures.USD;
import static com.basis.support.Fixtures.buy;
import static com.basis.support.Fixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;

import com.basis.domain.Commodity;
import com.basis.domain.Transaction;
import com.basis.ledger.Ledger;
import com.basis.ledger.LedgerAccounts;
import com.basis.persistence.BreakRecordRepository;
import com.basis.persistence.ImportBatchRepository;
import com.basis.persistence.LedgerRepository;
import com.basis.persistence.ReferenceDataRepository;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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
 * The loop the product exists for, run end to end through the commands.
 *
 * <p>Import a history missing a split. Reconcile against the broker and get a break that
 * names the split. Apply the break. Reconcile again and get nothing. Until {@code apply}
 * existed the third step was impossible, which made the second step a dead end.
 */
@SpringBootTest
@Testcontainers
class ClosingTheLoopTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final LocalDate SPLIT_DATE = JAN_15.plusMonths(1);
    private static final LocalDate AS_OF = JAN_15.plusMonths(3);

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
    com.basis.reference.FxRefreshService fxRefresh;

    @Autowired
    com.basis.reconcile.ExchangeRates rates;

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
        newCli();
    }

    private void newCli() {
        stdout = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(stdout, true, StandardCharsets.UTF_8);
        cli = new BasisCli(projector, derived, breaks, referenceData, rates, refresh, fxRefresh,
                recovery, importer,
                new CliOutput(stream, stream));
    }

    private int run(String... args) {
        cli.run(new DefaultApplicationArguments(args));
        return cli.getExitCode();
    }

    private String printed() {
        return stdout.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a split recorded by hand turns a suspicion into something apply break will act on")
    void aHandRecordedSplitUpgradesASuspicion(@TempDir Path dir) throws Exception {
        // Without this there was no way to tell basis about a split it could not fetch.
        // The provider can be wrong, can paywall a symbol, or can be absent entirely with
        // no key, and apply break refuses a suspicion on purpose. That combination left a
        // break nothing could confirm and therefore nothing could ever close.
        givenAPurchaseOf10Apple();
        Path positions = write(dir, "AAPL,40\n");

        run("reconcile", IBKR.name(), positions.toString(), "--as-of=" + AS_OF);
        assertThat(printed())
                .as("no reference data, so arithmetic and not evidence")
                .contains("(suspected)")
                .doesNotContain("basis apply break");

        newCli();
        assertThat(run("cache-split", "AAPL", "4:1", "--on=" + SPLIT_DATE)).isEqualTo(BasisCli.OK);
        assertThat(printed())
                .as("where a fact came from is part of the fact")
                .contains("manual");

        newCli();
        run("reconcile", IBKR.name(), positions.toString(), "--as-of=" + AS_OF);
        assertThat(printed())
                .as("the same numbers, now with something behind them")
                .contains("(confirmed)")
                .contains("basis apply break");
    }

    @Test
    @DisplayName("the break id is discoverable from the output, with no database access")
    void theLoopClosesFromTheOutputAlone(@TempDir Path dir) throws Exception {
        // theWholeLoop below reads the id straight out of break_record with SQL, which is
        // what hid this: every command that acts on a break takes an id, and nothing that
        // printed a break ever showed one. Somebody following the output was told to run
        // "apply break <id>" with no way to find one short of opening a psql session.
        givenAPurchaseOf10Apple();
        referenceData.cacheSplit("AAPL", SPLIT_DATE, 4, 1, "test");
        Path positions = write(dir, "AAPL,40\n");

        run("reconcile", IBKR.name(), positions.toString(), "--as-of=" + AS_OF);
        Matcher fromReconcile = Pattern.compile("basis apply break (\\d+)").matcher(printed());
        assertThat(fromReconcile.find())
                .as("reconcile should print the exact command, not just describe it")
                .isTrue();
        String id = fromReconcile.group(1);

        newCli();
        assertThat(run("breaks", IBKR.name())).isEqualTo(BasisCli.BREAKS_FOUND);
        assertThat(printed())
                .as("listing breaks has to show the same handle")
                .contains("[" + id + "]");

        newCli();
        assertThat(run("apply", "break", id))
                .as("the command the output gave actually works")
                .isEqualTo(BasisCli.OK);
    }

    @Test
    @DisplayName("a dry run prints no break id, because it stored no break to have one")
    void aDryRunHasNoIdToOffer(@TempDir Path dir) throws Exception {
        givenAPurchaseOf10Apple();
        referenceData.cacheSplit("AAPL", SPLIT_DATE, 4, 1, "test");
        Path positions = write(dir, "AAPL,40\n");

        run("reconcile", IBKR.name(), positions.toString(), "--as-of=" + AS_OF, "--dry-run");

        assertThat(printed())
                .as("offering a command with an id nothing assigned would be a lie")
                .doesNotContain("basis apply break")
                .contains("not recorded");
        assertThat(breaks.countOpen()).isZero();
    }

    @Test
    @DisplayName("reconcile finds a split, apply break applies it, and reconcile comes back clean")
    void theWholeLoop(@TempDir Path dir) throws Exception {
        givenAPurchaseOf10Apple();
        referenceData.cacheSplit("AAPL", SPLIT_DATE, 4, 1, "test");
        Path positions = write(dir, "AAPL,40\n");

        assertThat(run("reconcile", IBKR.name(), positions.toString(), "--as-of=" + AS_OF))
                .isEqualTo(BasisCli.BREAKS_FOUND);
        assertThat(printed()).contains("next: Apply the 4 for 1 split");
        long id = db.sql("SELECT id FROM break_record ORDER BY id LIMIT 1").query(Long.class).single();

        newCli();
        assertThat(run("apply", "break", String.valueOf(id))).isEqualTo(BasisCli.OK);
        assertThat(printed())
                .contains("applied")
                .contains("settled as ACCEPTED");

        newCli();
        assertThat(run("reconcile", IBKR.name(), positions.toString(), "--as-of=" + AS_OF))
                .as("the split is applied, so basis and the broker now agree")
                .isEqualTo(BasisCli.OK);
        assertThat(printed()).contains("no breaks");
        assertThat(breaks.countOpen()).isZero();
    }

    @Test
    @DisplayName("apply break refuses a suspicion, because a ratio alone is not evidence")
    void refusesToActOnASuspicion(@TempDir Path dir) throws Exception {
        givenAPurchaseOf10Apple();
        Path positions = write(dir, "AAPL,40\n");
        run("reconcile", IBKR.name(), positions.toString(), "--as-of=" + AS_OF);
        long id = db.sql("SELECT id FROM break_record ORDER BY id LIMIT 1").query(Long.class).single();

        newCli();
        assertThat(run("apply", "break", String.valueOf(id))).isEqualTo(BasisCli.USAGE);
        assertThat(printed())
                .as("no reference data was fetched, so nothing corroborates the ratio")
                .contains("is a suspicion, not a finding");
        assertThat(breaks.countOpen()).isEqualTo(1);
    }

    @Test
    @DisplayName("an opening balance seeds a position older than any statement")
    void openingBalanceSeedsAHolding() {
        assertThat(run("open", IBKR.name(), "AAPL", "100", "--cost=90.00", "--on=2015-03-12"))
                .isEqualTo(BasisCli.OK);

        assertThat(projector.project().position(LedgerAccounts.holding(IBKR, AAPL), AAPL).value())
                .isEqualByComparingTo("100");
        assertThat(projector.project().openBasis(LedgerAccounts.holding(IBKR, AAPL), AAPL, USD))
                .isEqualTo(usd("9000.00"));
    }

    @Test
    @DisplayName("an opening balance of a security without a cost is refused")
    void openingBalanceNeedsACost() {
        assertThat(run("open", IBKR.name(), "AAPL", "100", "--on=2015-03-12"))
                .isEqualTo(BasisCli.USAGE);
        assertThat(printed()).contains("needs --cost");
    }

    @Test
    @DisplayName("applying the same corporate action twice changes nothing the second time")
    void applyingTwiceIsSafe() {
        givenAPurchaseOf10Apple();

        assertThat(run("apply", "split", IBKR.name(), "AAPL", "4:1", "--on=" + SPLIT_DATE))
                .isEqualTo(BasisCli.OK);
        newCli();
        assertThat(run("apply", "split", IBKR.name(), "AAPL", "4:1", "--on=" + SPLIT_DATE))
                .isEqualTo(BasisCli.OK);
        assertThat(printed())
                .as("a nervous second run should correct nothing and duplicate nothing")
                .contains("already recorded");

        assertThat(projector.project().position(LedgerAccounts.holding(IBKR, AAPL), AAPL).value())
                .isEqualByComparingTo("40");
    }

    @Test
    @DisplayName("a reverse split with cash in lieu sells the fraction it left behind")
    void cashInLieuSellsTheFraction() {
        givenAPurchaseOf10Apple();

        // 10 shares at 1 for 4 leaves 2.5, and nobody can hold half a share.
        assertThat(run("apply", "reverse-split", IBKR.name(), "AAPL", "1:4",
                "--on=" + SPLIT_DATE, "--cash-in-lieu=310.00")).isEqualTo(BasisCli.OK);

        assertThat(projector.project().position(LedgerAccounts.holding(IBKR, AAPL), AAPL).value())
                .as("the fraction was sold, leaving whole shares")
                .isEqualByComparingTo("2");
        assertThat(projector.project().realizedGains())
                .as("cash in lieu is a real disposal, and often the only taxable part of a split")
                .isNotEmpty();
    }

    @Test
    @DisplayName("average cost is elected for a fund, and refused for an equity")
    void averageCostIsElectedNotSelected() {
        Commodity fund = Commodity.mutualFund("VTSAX");
        Ledger ledger = new Ledger();
        long batch = batches.open("test", "seed.csv", new byte[] {1});
        ledgerRepository.append(batch, ledger.record(com.basis.support.Fixtures.buy(
                JAN_15, "b1", fund, "100", "10.00", "0.00")));
        ledgerRepository.append(batch, ledger.record(com.basis.support.Fixtures.buy(
                JAN_15.plusDays(30), "b2", fund, "100", "20.00", "0.00")));
        batches.commit(batch, 2);
        projector.rebuild();

        assertThat(run("apply", "average-cost", IBKR.name(), "VTSAX", "--on=" + AS_OF))
                .isEqualTo(BasisCli.OK);

        assertThat(projector.project().openLots(LedgerAccounts.holding(IBKR, fund), fund))
                .allSatisfy(lot -> assertThat(lot.unitCost().value())
                        .as("both lots now carry the pooled cost of 15.00")
                        .isEqualByComparingTo("15.00"));
        assertThat(projector.project().openBasis(LedgerAccounts.holding(IBKR, fund), fund, USD))
                .as("averaging does not change what the position cost")
                .isEqualTo(usd("3000.00"));

        newCli();
        assertThat(run("apply", "average-cost", IBKR.name(), "AAPL", "--on=" + AS_OF, "--kind=EQUITY"))
                .isEqualTo(BasisCli.FAILED);
        assertThat(printed())
                .as("no broker offering it makes an equity eligible")
                .contains("not permitted");
    }

    @Test
    @DisplayName("an interrupted import is resumed by recovering and importing the file again")
    void resumeIsRecoverThenReimport(@TempDir Path dir) throws Exception {
        Path statement = dir.resolve("history.csv");
        Files.writeString(statement, """
                Run Date,Action,Symbol,Quantity,Price ($),Amount ($)
                01/15/2026,YOU BOUGHT,AAPL,10,150.00,-1500.00
                02/01/2026,YOU BOUGHT,MSFT,5,300.00,-1500.00
                """, StandardCharsets.UTF_8);

        // A crash mid import: the batch is open and one transaction is in.
        Ledger ledger = new Ledger();
        long interrupted = batches.open("fidelity", "history.csv", new byte[] {9});
        ledgerRepository.append(interrupted, ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00")));
        assertThat(batches.findInFlight()).containsExactly(interrupted);

        assertThat(run("recover")).isEqualTo(BasisCli.OK);
        assertThat(printed()).contains("rolled back 1");

        newCli();
        assertThat(run("import", "fidelity", IBKR.name(), statement.toString())).isEqualTo(BasisCli.OK);
        assertThat(printed()).contains("2 transaction(s) recorded");
        assertThat(batches.findInFlight())
                .as("resume is recover then re-import, and idempotency is what makes that safe")
                .isEmpty();
    }

    @Test
    @DisplayName("reconciling twice leaves one break, not a growing pile of identical ones")
    void reconcilingIsIdempotent(@TempDir Path dir) throws Exception {
        givenAPurchaseOf10Apple();
        Path positions = write(dir, "AAPL,40\n");

        run("reconcile", IBKR.name(), positions.toString(), "--as-of=" + AS_OF);
        newCli();
        run("reconcile", IBKR.name(), positions.toString(), "--as-of=" + AS_OF);

        assertThat(breaks.countOpen())
                .as("a break is the current state of a disagreement, not an entry in a log")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a break that no longer applies is closed by the next reconcile")
    void staleBreaksAreCleared(@TempDir Path dir) throws Exception {
        givenAPurchaseOf10Apple();
        run("reconcile", IBKR.name(), write(dir, "AAPL,40\n").toString(), "--as-of=" + AS_OF);
        assertThat(breaks.countOpen()).isEqualTo(1);

        newCli();
        Path agreeing = dir.resolve("agreeing.csv");
        Files.writeString(agreeing, "AAPL,10\n", StandardCharsets.UTF_8);
        assertThat(run("reconcile", IBKR.name(), agreeing.toString(), "--as-of=" + AS_OF))
                .isEqualTo(BasisCli.OK);

        assertThat(breaks.countOpen())
                .as("the holding agrees now, so keeping the old break open would be a lie")
                .isZero();
    }

    @Test
    @DisplayName("a settled break survives a later reconcile, because it carries a judgement")
    void settledBreaksSurvive(@TempDir Path dir) throws Exception {
        givenAPurchaseOf10Apple();
        Path positions = write(dir, "AAPL,40\n");
        run("reconcile", IBKR.name(), positions.toString(), "--as-of=" + AS_OF);
        long id = db.sql("SELECT id FROM break_record ORDER BY id LIMIT 1").query(Long.class).single();
        breaks.settle(id, com.basis.reconcile.BreakStatus.REJECTED, "the statement was wrong");

        newCli();
        run("reconcile", IBKR.name(), positions.toString(), "--as-of=" + AS_OF);

        assertThat(db.sql("SELECT count(*) FROM break_record WHERE status = 'REJECTED'")
                .query(Integer.class).single())
                .as("break_record is not derived state precisely because of this")
                .isEqualTo(1);
    }

    private void givenAPurchaseOf10Apple() {
        Ledger ledger = new Ledger();
        long batch = batches.open("test", "seed.csv", new byte[] {1});
        Transaction txn = ledger.record(buy(JAN_15, "b1", AAPL, "10", "150.00", "0.00"));
        ledgerRepository.append(batch, txn);
        batches.commit(batch, 1);
        projector.rebuild();
    }

    private static Path write(Path dir, String content) throws Exception {
        Path file = dir.resolve("positions.csv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
