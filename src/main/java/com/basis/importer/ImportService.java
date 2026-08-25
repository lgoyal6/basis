package com.basis.importer;

import com.basis.domain.Account;
import com.basis.domain.Transaction;
import com.basis.domain.event.LedgerEvent;
import com.basis.ledger.Ledger;
import com.basis.persistence.DerivedStateProjector;
import com.basis.persistence.ImportBatchRepository;
import com.basis.persistence.LedgerRepository;
import com.basis.reference.SymbolMapping;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads a statement into the ledger.
 *
 * <p>The batch lifecycle is the crash story that week 1 built the schema for and nothing
 * has exercised until now. A batch is opened before the first write and committed after the
 * last one, so a process killed halfway leaves a row with a null {@code committed_at} that
 * startup recovery rolls back. Until this class existed, that mechanism had no way of ever
 * firing outside a test.
 *
 * <p>Parsing happens <b>before</b> the batch opens. A file that cannot be read should not
 * leave an abandoned batch behind, and finding out on row 400 that row 12 was unreadable is
 * worse than finding out before anything was written.
 *
 * <p>Not annotated transactional. The batch is deliberately not one database transaction:
 * the point of the marker is that a half written import is visible and recoverable, and
 * wrapping the whole thing would make it invisible and roll itself back, which sounds better
 * and would leave nothing to recover from and no record that anyone tried.
 */
@Service
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    /** The batch source for events a person entered rather than a broker reported. */
    static final String ASSERTED = "asserted";

    private final ImportBatchRepository batches;
    private final LedgerRepository ledgerRepository;
    private final DerivedStateProjector projector;

    public ImportService(
            ImportBatchRepository batches,
            LedgerRepository ledgerRepository,
            DerivedStateProjector projector) {
        this.batches = batches;
        this.ledgerRepository = ledgerRepository;
        this.projector = projector;
    }

    /**
     * Imports a broker statement into an account.
     *
     * @param profile how to read this broker's export. Nothing below this line knows which
     *     broker it is, which is what makes a second broker a properties file.
     * @param externalAccount where deposits come from and withdrawals go, so cash entering
     *     the ledger has a source rather than appearing from nowhere
     */
    public ImportReport importStatement(
            Path file, BrokerProfile profile, Account brokerAccount, Account externalAccount,
            SymbolMapping renames) {

        List<StatementRow> rows = new StatementParser(profile).read(file);
        String source = file.getFileName().toString();
        StatementRowMapper mapper =
                new StatementRowMapper(profile, brokerAccount, externalAccount, renames, source);

        // Every row is understood before anything is written. A statement with one
        // unreadable line is rejected whole, rather than half imported.
        List<LedgerEvent> events = new ArrayList<>();
        int ignored = 0;
        for (StatementRow row : rows) {
            List<LedgerEvent> fromRow = mapper.toEvents(row);
            if (fromRow.isEmpty()) {
                ignored++;
            }
            events.addAll(fromRow);
        }

        ImportReport report = record(events, rows.size(), file, profile);
        if (ignored == 0) {
            return report;
        }
        // Counted and reported. A row skipped on purpose is still a row somebody should be
        // able to notice was skipped.
        List<String> notes = new ArrayList<>(report.notes());
        notes.add(ignored + " row(s) were ignored by the " + profile.name()
                + " profile's action.IGNORE list.");
        return new ImportReport(report.batchId(), report.source(), report.rowsRead(),
                report.eventsRecorded(), report.alreadyPresent(), notes);
    }

    /**
     * Records an event the user asserts rather than one a broker reported.
     *
     * <p>Corporate actions, opening balances and corrections do not arrive on a statement,
     * or arrive in a form no parser should be trusted to read. They still go through the
     * same batch, the same idempotency key and the same rebuild, because an event nobody can
     * audit is worse than one nobody can enter.
     *
     * <p>The command that produced it is the source row. For a statement the verbatim line
     * is what makes a parser bug fixable by replay; for an assertion, what someone typed is
     * the equivalent record of where the entry came from.
     *
     * <p>Re-running the same command is a no op, because the reference is derived from the
     * command's own content rather than generated.
     */
    public ImportReport recordAsserted(LedgerEvent event, Path pseudoFile) {
        return record(List.of(event), 1, pseudoFile, ASSERTED);
    }

    /**
     * Replays events through the ledger and writes them, inside one import batch.
     *
     * <p>The events are applied to a ledger hydrated from what is already stored, so a sale
     * in this file can consume lots opened by a file imported last month.
     */
    private ImportReport record(List<LedgerEvent> events, int rowsRead, Path file, BrokerProfile profile) {
        return record(events, rowsRead, file, profile.name(), digestOf(file));
    }

    private ImportReport record(List<LedgerEvent> events, int rowsRead, Path file, String source) {
        // An asserted event has no file to hash, so its content is the identity instead.
        byte[] identity = events.stream()
                .map(event -> event.idempotencyKey().bytes())
                .findFirst()
                .orElse(new byte[] {0});
        return record(events, rowsRead, file, source, identity);
    }

    private ImportReport record(
            List<LedgerEvent> events, int rowsRead, Path file, String source, byte[] contentHash) {
        Ledger ledger = hydratedLedger();
        long batchId = batches.open(source, file.getFileName().toString(), contentHash);
        List<String> notes = new ArrayList<>();
        int recorded = 0;
        int alreadyPresent = 0;

        try {
            for (LedgerEvent event : events) {
                // Asked before the event is replayed. A purchase already in the ledger would
                // otherwise try to reopen a lot that is already open, and the ledger would
                // refuse it before the unique key constraint ever saw it. Overlapping
                // statements are the normal way to use this, so that path has to be quiet.
                if (ledgerRepository.exists(event.idempotencyKey())) {
                    alreadyPresent++;
                    continue;
                }
                Transaction transaction = ledger.record(event);
                if (ledgerRepository.append(batchId, transaction)) {
                    recorded++;
                } else {
                    alreadyPresent++;
                }
            }
            batches.commit(batchId, rowsRead);
        } catch (RuntimeException e) {
            // The batch is left in flight on purpose. Startup recovery, or basis recover,
            // rolls it back and says so, which leaves a trail that an import was attempted.
            log.error("import of {} failed after {} transaction(s); batch {} is left in flight"
                    + " for recovery to roll back", file, recorded, batchId);
            throw e;
        }

        if (alreadyPresent > 0) {
            notes.add(alreadyPresent + " transaction(s) were already in the ledger and were skipped."
                    + " Overlapping statements are normal.");
        }
        projector.rebuild();
        return new ImportReport(batchId, file.getFileName().toString(), rowsRead, recorded, alreadyPresent, notes);
    }

    /**
     * A ledger holding everything already imported.
     *
     * <p>Rebuilt from postings rather than kept in memory between runs, which is the same
     * projection invariant 7 tests. A sale needs the lots its purchase opened, and those
     * were written by a different process on a different day.
     */
    private Ledger hydratedLedger() {
        return new Ledger(projector.project());
    }

    /** Identifies the file's content, so the batch records what was imported and not just its name. */
    private static byte[] digestOf(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(Files.readString(file, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
