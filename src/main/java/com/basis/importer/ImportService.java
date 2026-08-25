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
     * Imports a Fidelity statement into an account.
     *
     * @param externalAccount where deposits come from and withdrawals go, so cash entering
     *     the ledger has a source rather than appearing from nowhere
     */
    public ImportReport importFidelity(
            Path file, Account brokerAccount, Account externalAccount, SymbolMapping renames) {

        List<StatementRow> rows = FidelityCsvParser.read(file);
        String source = file.getFileName().toString();
        StatementRowMapper mapper = new StatementRowMapper(brokerAccount, externalAccount, renames, source);

        // Every row is understood before anything is written. A statement with one
        // unreadable line is rejected whole, rather than half imported.
        List<LedgerEvent> events = new ArrayList<>();
        for (StatementRow row : rows) {
            events.addAll(mapper.toEvents(row));
        }

        return record(events, rows.size(), file, brokerAccount);
    }

    /**
     * Replays events through the ledger and writes them, inside one import batch.
     *
     * <p>The events are applied to a ledger hydrated from what is already stored, so a sale
     * in this file can consume lots opened by a file imported last month.
     */
    private ImportReport record(List<LedgerEvent> events, int rowsRead, Path file, Account brokerAccount) {
        Ledger ledger = hydratedLedger();
        long batchId = batches.open("fidelity", file.getFileName().toString(), digestOf(file));
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
