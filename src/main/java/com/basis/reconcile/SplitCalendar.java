package com.basis.reconcile;

import com.basis.domain.Commodity;
import java.time.LocalDate;
import java.util.List;

/**
 * What corporate actions the reference data knows about for a security.
 *
 * <p>An interface with an empty default, so reconciliation degrades rather than fails when
 * no reference data has been fetched. Without it a break says "this looks like a 4 for 1
 * ratio"; with it the same break says "there is a 4 for 1 split on 2020-08-31". Both are
 * useful, and the difference is exactly the difference between a suspicion and a finding.
 */
public interface SplitCalendar {

    /** Knows nothing. What reconciliation uses before any reference data has been fetched. */
    SplitCalendar EMPTY = (commodity, from, to) -> List.of();

    /** Splits for the commodity with an effective date in the closed range, oldest first. */
    List<KnownSplit> splitsBetween(Commodity commodity, LocalDate from, LocalDate to);
}
