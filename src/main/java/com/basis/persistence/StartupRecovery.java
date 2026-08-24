package com.basis.persistence;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Resolves import batches that were in flight when the process last stopped.
 *
 * <p>A batch with a null {@code committed_at} and a null {@code abandoned_at} is a
 * crash. It is rolled back here, never left as it was found. Ambiguity is the failure
 * mode this whole mechanism exists to prevent: a half imported statement that looks
 * imported is how a reconciliation tool ends up confidently reporting a break that it
 * caused itself.
 *
 * <p>Week 1 rolls back rather than resumes. Resuming means knowing which source rows
 * were already consumed, which is parser state, and there is no parser until week 2.
 * Rollback is safe because {@code txn.source_row} keeps every original row verbatim, so
 * the correct recovery is always to replay the file. See docs/ARCHITECTURE.md section 9.
 */
@Component
public class StartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    static final String REASON = "rolled back on startup: in flight when the process stopped";

    private final ImportBatchRepository batches;
    private final DerivedStateProjector projector;

    public StartupRecovery(ImportBatchRepository batches, DerivedStateProjector projector) {
        this.batches = batches;
        this.projector = projector;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        recover();
    }

    /**
     * Rolls back every in flight batch, then rebuilds derived state if anything changed.
     *
     * @return the batch ids that were rolled back
     */
    public List<Long> recover() {
        List<Long> inFlight = batches.findInFlight();
        if (inFlight.isEmpty()) {
            log.debug("no import batches were in flight");
            return List.of();
        }
        for (Long batchId : inFlight) {
            int discarded = batches.countTransactions(batchId);
            batches.abandon(batchId, REASON);
            log.warn("rolled back import batch {}: discarded {} transactions written before the crash."
                    + " Re import the source file to replay it.", batchId, discarded);
        }
        // Those transactions took their postings with them, so anything derived from
        // them is now wrong. Rebuild rather than patch: that is what derived means.
        projector.rebuild();
        return inFlight;
    }
}
