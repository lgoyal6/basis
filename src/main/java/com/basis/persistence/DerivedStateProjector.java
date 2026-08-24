package com.basis.persistence;

import com.basis.domain.Posting;
import com.basis.domain.TxnId;
import com.basis.ledger.LedgerState;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rebuilds all derived state from the posting table.
 *
 * <p>Truncate, read postings in id order, fold them through a {@link LedgerState},
 * write the result. The fold is the same {@code LedgerState} the in memory ledger uses,
 * not a second implementation of the same arithmetic: if the projector had its own copy,
 * invariant 7 would be comparing two code paths rather than proving that one code path
 * is deterministic.
 */
@Component
public class DerivedStateProjector {

    private static final Logger log = LoggerFactory.getLogger(DerivedStateProjector.class);

    private final LedgerRepository ledger;
    private final DerivedStateRepository derived;

    public DerivedStateProjector(LedgerRepository ledger, DerivedStateRepository derived) {
        this.ledger = ledger;
        this.derived = derived;
    }

    /** Truncates and rebuilds. Idempotent, and safe to run at any time. */
    @Transactional
    public LedgerState rebuild() {
        derived.truncate();
        LedgerState state = project();
        derived.write(state);
        // Debug rather than info: the caller reports this, and saying it twice is worse
        // than saying it once.
        log.debug("rebuilt derived state: {} positions, {} lots, {} realized gains",
                state.positions().size(), state.allLots().size(), state.realizedGains().size());
        return state;
    }

    /**
     * Folds the posting table into derived state without writing anything.
     *
     * <p>Postings are read in id order and grouped into the transactions they belong to.
     * A transaction's postings have to be applied together, because the realized gain is
     * read off the whole set rather than off any one leg.
     */
    public LedgerState project() {
        LedgerState state = new LedgerState();
        List<PostingRow> rows = ledger.readAllInReplayOrder();
        Set<TxnId> completed = new HashSet<>();

        TxnId currentTxn = null;
        LocalDate currentDate = null;
        List<Posting> batch = new ArrayList<>();

        for (PostingRow row : rows) {
            if (!row.weightAgreesWithCode()) {
                throw new IllegalStateException("posting " + row.id() + " was stored with weight "
                        + row.storedWeightMinor() + " but the code now computes "
                        + row.posting().weight().minorUnits()
                        + ". The rounding rule changed under an existing ledger.");
            }
            if (currentTxn != null && !currentTxn.equals(row.txnId())) {
                state.applyPostings(currentTxn, currentDate, batch);
                completed.add(currentTxn);
                batch = new ArrayList<>();
            }
            if (completed.contains(row.txnId())) {
                // Postings of one transaction are written in a single statement batch by
                // a single writer, so they are contiguous in id order. If they are not,
                // replay order and transaction grouping disagree and the fold would be
                // silently wrong, so say so instead.
                throw new IllegalStateException("postings of transaction " + row.txnId()
                        + " are not contiguous in id order; replay grouping cannot be trusted");
            }
            currentTxn = row.txnId();
            currentDate = row.date();
            batch.add(row.posting());
        }
        if (currentTxn != null) {
            state.applyPostings(currentTxn, currentDate, batch);
        }
        return state;
    }
}
