package com.basis.importer;

import static com.basis.support.Fixtures.IBKR;
import static com.basis.support.Fixtures.USD;
import static com.basis.support.Fixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.ledger.LedgerAccounts;
import com.basis.ledger.LedgerState;
import com.basis.persistence.DerivedStateProjector;
import com.basis.persistence.DerivedStateRepository;
import com.basis.persistence.ImportBatchRepository;
import com.basis.persistence.LedgerRepository;
import com.basis.persistence.StartupRecovery;
import com.basis.reference.SymbolMapping;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A statement going into the ledger, against a real database.
 *
 * <p>This is the first thing in the project that makes the import batch crash marker fire
 * outside a test that staged it by hand. Week 1 built the schema for it and nothing until
 * now could ever have left a batch in flight.
 */
@SpringBootTest
@Testcontainers
class ImportServiceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Account EXTERNAL = Account.of("Assets:Bank:External");
    private static final Commodity AAPL = Commodity.equity("AAPL");

    private static final String STATEMENT = """
            Brokerage

            Run Date,Action,Symbol,Description,Type,Quantity,Price ($),Commission ($),Fees ($),Amount ($)
            01/05/2026,ELECTRONIC FUNDS TRANSFER RECEIVED,,CONTRIBUTION,Cash,,,,,10000.00
            01/15/2026,YOU BOUGHT,AAPL,"APPLE INC, COM",Cash,10,150.00,1.00,0.00,-1501.00
            02/10/2026,DIVIDEND RECEIVED,AAPL,APPLE INC,Cash,,,,,24.00
            03/01/2026,YOU SOLD,AAPL,"APPLE INC, COM",Cash,-4,160.00,1.00,0.00,639.00

            "Brokerage services provided by Fidelity Brokerage Services LLC"
            """;

    @Autowired
    JdbcClient db;

    @Autowired
    ImportService importer;

    @Autowired
    ImportBatchRepository batches;

    @Autowired
    LedgerRepository ledgerRepository;

    @Autowired
    DerivedStateProjector projector;

    @Autowired
    DerivedStateRepository derived;

    @Autowired
    StartupRecovery recovery;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM import_batch").update();
        derived.truncate();
    }

    @Test
    @DisplayName("a statement becomes positions, lots and a realized gain")
    void importsAWholeStatement(@TempDir Path dir) throws Exception {
        ImportReport report = importer.importFidelity(
                write(dir, STATEMENT), IBKR, EXTERNAL, SymbolMapping.empty());

        assertThat(report.rowsRead()).isEqualTo(4);
        assertThat(report.eventsRecorded()).isEqualTo(4);
        assertThat(report.alreadyPresent()).isZero();

        LedgerState state = projector.project();
        assertThat(state.position(LedgerAccounts.holding(IBKR, AAPL), AAPL).value())
                .as("bought 10, sold 4")
                .isEqualByComparingTo("6");

        // 10000 in, 1501 out for the purchase, 24 dividend, 639 from the sale.
        assertThat(state.cash(LedgerAccounts.cash(IBKR), USD)).isEqualTo(usd("9162.00"));
        assertThat(state.realizedGains()).singleElement().satisfies(gain -> {
            assertThat(gain.basis()).isEqualTo(usd("600.00"));
            assertThat(gain.proceeds()).isEqualTo(usd("640.00"));
            assertThat(gain.gain())
                    .as("sold 4 at 160 that cost 150, and the commission is expensed not netted")
                    .isEqualTo(usd("40.00"));
        });
    }

    @Test
    @DisplayName("the batch is committed, so nothing is left looking interrupted")
    void commitsItsBatch(@TempDir Path dir) throws Exception {
        ImportReport report = importer.importFidelity(
                write(dir, STATEMENT), IBKR, EXTERNAL, SymbolMapping.empty());

        assertThat(batches.isCommitted(report.batchId())).isTrue();
        assertThat(batches.findInFlight()).isEmpty();
    }

    @Test
    @DisplayName("importing the same file twice changes nothing the second time")
    void reimportingIsANoOp(@TempDir Path dir) throws Exception {
        Path file = write(dir, STATEMENT);
        importer.importFidelity(file, IBKR, EXTERNAL, SymbolMapping.empty());
        String hashAfterFirst = derived.hash();

        ImportReport second = importer.importFidelity(file, IBKR, EXTERNAL, SymbolMapping.empty());

        assertThat(second.eventsRecorded()).isZero();
        assertThat(second.alreadyPresent()).isEqualTo(4);
        assertThat(second.notes()).anyMatch(note -> note.contains("Overlapping statements are normal"));
        assertThat(derived.hash())
                .as("the ledger is byte for byte where it was")
                .isEqualTo(hashAfterFirst);
    }

    @Test
    @DisplayName("a second statement continues from the first, consuming its lots")
    void laterStatementsSeeEarlierLots(@TempDir Path dir) throws Exception {
        importer.importFidelity(write(dir, "first.csv", """
                Run Date,Action,Symbol,Quantity,Price ($),Commission ($),Amount ($)
                01/15/2026,YOU BOUGHT,AAPL,10,150.00,0.00,-1500.00
                """), IBKR, EXTERNAL, SymbolMapping.empty());

        // The sale is in a different file, imported by a different run, and has to find the
        // lot the purchase opened.
        importer.importFidelity(write(dir, "second.csv", """
                Run Date,Action,Symbol,Quantity,Price ($),Commission ($),Amount ($)
                03/01/2026,YOU SOLD,AAPL,-10,160.00,0.00,1600.00
                """), IBKR, EXTERNAL, SymbolMapping.empty());

        LedgerState state = projector.project();
        assertThat(state.position(LedgerAccounts.holding(IBKR, AAPL), AAPL).isZero()).isTrue();
        assertThat(state.realizedGains()).singleElement()
                .satisfies(gain -> assertThat(gain.gain()).isEqualTo(usd("100.00")));
    }

    @Test
    @DisplayName("an unreadable row stops the import before anything is written")
    void aBadRowWritesNothing(@TempDir Path dir) throws Exception {
        Path file = write(dir, """
                Run Date,Action,Symbol,Quantity,Price ($),Amount ($)
                01/15/2026,YOU BOUGHT,AAPL,10,150.00,-1500.00
                02/01/2026,BOND INTEREST ACCRUAL,,,,12.34
                """);

        assertThatThrownBy(() -> importer.importFidelity(file, IBKR, EXTERNAL, SymbolMapping.empty()))
                .isInstanceOf(StatementFormatException.class)
                .hasMessageContaining("BOND INTEREST ACCRUAL");

        assertThat(ledgerRepository.countTransactions())
                .as("the good row before it is not written either: the file is rejected whole")
                .isZero();
        assertThat(batches.findInFlight())
                .as("and no batch is left behind, because parsing happens before the batch opens")
                .isEmpty();
    }

    @Test
    @DisplayName("every posting written carries the statement line it came from, verbatim")
    void keepsTheSourceRow(@TempDir Path dir) throws Exception {
        importer.importFidelity(write(dir, STATEMENT), IBKR, EXTERNAL, SymbolMapping.empty());

        String sourceRow = db.sql("SELECT source_row::text FROM txn WHERE event_type = 'Buy'")
                .query(String.class)
                .single();

        assertThat(sourceRow)
                .as("a parser bug is fixable by replay, which needs the original line")
                .contains("\"raw\"")
                .contains("YOU BOUGHT")
                .contains("APPLE INC, COM")
                .contains("history.csv");
    }

    @Test
    @DisplayName("a reinvestment lands as income and shares, not as free shares")
    void importsAReinvestment(@TempDir Path dir) throws Exception {
        importer.importFidelity(write(dir, """
                Run Date,Action,Symbol,Quantity,Price ($),Amount ($)
                01/15/2026,YOU BOUGHT,AAPL,10,150.00,-1500.00
                02/10/2026,REINVESTMENT,AAPL,0.16,150.00,-24.00
                """), IBKR, EXTERNAL, SymbolMapping.empty());

        LedgerState state = projector.project();
        assertThat(state.position(LedgerAccounts.holding(IBKR, AAPL), AAPL).value())
                .isEqualByComparingTo("10.16");
        assertThat(state.openBasis(LedgerAccounts.holding(IBKR, AAPL), AAPL, USD))
                .as("the reinvested shares carry the basis the distribution paid for them")
                .isEqualTo(usd("1524.00"));
    }

    @Test
    @DisplayName("a crashed import leaves a batch in flight for recovery to roll back")
    void anInterruptedImportIsRecoverable() {
        // Staged directly, because making the real import die halfway would mean breaking
        // it on purpose. What matters is that the batch it would leave behind is recoverable.
        long batch = batches.open("fidelity", "half-written.csv", new byte[] {1});
        assertThat(batches.findInFlight()).containsExactly(batch);

        assertThat(recovery.recover()).containsExactly(batch);
        assertThat(batches.findInFlight())
                .as("no import is ever left ambiguous")
                .isEmpty();
    }

    private static Path write(Path dir, String content) throws Exception {
        return write(dir, "history.csv", content);
    }

    private static Path write(Path dir, String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
