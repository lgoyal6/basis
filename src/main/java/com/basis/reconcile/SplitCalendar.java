package com.basis.reconcile;

import com.basis.domain.Commodity;
import java.time.LocalDate;

/**
 * What the reference data knows about a security's splits, and whether it knows anything.
 *
 * <p>Returns {@link SplitCoverage} rather than a bare list because an empty list is
 * ambiguous in a way that matters. "We asked the provider and this security had no splits"
 * rules a corporate action out. "Nobody has asked" rules nothing out and is a reason to go
 * and fetch. Handing both back as an empty list forces the reconciler to guess which one it
 * is holding, and it will guess wrong half the time.
 */
public interface SplitCalendar {

    /**
     * Knows nothing and says so. What reconciliation uses before any reference data has
     * been fetched, and what makes {@code Reconciler.withoutReferenceData()} honest rather
     * than merely empty.
     */
    SplitCalendar EMPTY = (commodity, from, to) -> SplitCoverage.neverChecked();

    /** Coverage for the commodity over the closed date range. */
    SplitCoverage coverageBetween(Commodity commodity, LocalDate from, LocalDate to);
}
