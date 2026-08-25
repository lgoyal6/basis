package com.basis.web;

import com.basis.reconcile.KnownSplit;
import com.basis.reconcile.SplitCalendar;
import com.basis.reconcile.SplitCoverage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The split history the demo needs, held apart from everybody else's.
 *
 * <p>The demo exists to show that basis can prove a break, not merely describe one. That
 * requires split history, and there are two obvious ways to get it, both wrong.
 *
 * <p>Writing these rows into {@code reference_data} would put demo state in the same table
 * real uploads read from. Even though the facts happen to be true, a seeded row is
 * indistinguishable from a fetched one once it is in there, and "the demo wrote to the
 * production cache" is the kind of thing that is fine until the day somebody seeds a fact
 * that is wrong.
 *
 * <p>Fetching them from the provider would make the demo depend on a network call, an API
 * key, and a quota. A demo that breaks when a third party is down is worse than no demo,
 * and it would be broken exactly when somebody is deciding whether to trust this.
 *
 * <p>So the demo carries its own calendar, in memory, alongside its own statement. Nothing
 * is written anywhere. These are real splits, which matters: a demo that proves a break
 * against an invented corporate action proves nothing about the lookup that will run on
 * somebody's actual holdings.
 */
final class DemoSplits implements SplitCalendar {

    /** Real corporate actions, dated as they really happened. */
    private static final List<Entry> KNOWN = List.of(
            new Entry("AAPL", LocalDate.of(2020, 8, 31), 4, 1));

    static final SplitCalendar CALENDAR = new DemoSplits();

    private record Entry(String symbol, LocalDate date, long numerator, long denominator) {
    }

    private DemoSplits() {
    }

    @Override
    public SplitCoverage coverageBetween(com.basis.domain.Commodity commodity, LocalDate from, LocalDate to) {
        List<KnownSplit> found = new ArrayList<>();
        for (Entry entry : KNOWN) {
            if (entry.symbol().equals(commodity.symbol())
                    && !entry.date().isBefore(from) && !entry.date().isAfter(to)) {
                found.add(new KnownSplit(commodity, entry.date(), entry.numerator(),
                        entry.denominator(), Instant.EPOCH));
            }
        }
        // CHECKED even when the list is empty, which is the whole point of the three way
        // answer. For a demo symbol basis genuinely has looked, so a ratio it cannot match
        // to a split is a ratio it can rule out, not one it has never examined.
        return SplitCoverage.checked(found, Instant.EPOCH);
    }
}
