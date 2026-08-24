package com.basis.reference;

import com.basis.persistence.ReferenceDataRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Spends a bounded number of provider requests on the symbols where they are worth most.
 *
 * <p>The ordering is the design. A symbol with an open break that a split would explain is
 * worth a request today; a symbol nobody holds is not worth one ever. Refreshing
 * alphabetically would spend the budget on whatever starts with A.
 *
 * <p>The budget is a ceiling this service imposes, not one the provider communicates. It
 * returns no rate limit headers of any kind, so there is nothing to read and back off
 * from, and guessing wrong means either burning a daily quota in one run or refreshing so
 * rarely the data is never current. See docs/FEASIBILITY.md.
 */
@Service
public class SplitRefreshService {

    private static final Logger log = LoggerFactory.getLogger(SplitRefreshService.class);

    private final SplitFeed feed;
    private final ReferenceDataRepository referenceData;
    private final FmpProperties properties;

    public SplitRefreshService(SplitFeed feed, ReferenceDataRepository referenceData, FmpProperties properties) {
        this.feed = feed;
        this.referenceData = referenceData;
        this.properties = properties;
    }

    /** Refreshes the symbols most in need of it, within the configured budget. */
    public RefreshReport refreshStaleSymbols() {
        List<String> symbols = referenceData.symbolsNeedingRefresh(
                properties.refreshAfter(), properties.dailyRequestBudget());
        return refresh(symbols);
    }

    /**
     * Refreshes exactly these symbols, in order, stopping early on a failure that would
     * repeat for every one of them.
     *
     * <p>A 402 is per symbol and the run continues past it: on the free tier most symbols
     * answer that way and stopping would mean never reaching the ones that work. A 401 is
     * not per symbol, so the run stops rather than marching through the whole list
     * recording the same wrong key against every holding.
     */
    public RefreshReport refresh(List<String> symbols) {
        if (!properties.enabled()) {
            log.info("split refresh is disabled, skipping {} symbols", symbols.size());
            return new RefreshReport(0, 0, 0, 0, List.of(), true);
        }
        properties.requireUsable();

        int attempted = 0;
        int succeeded = 0;
        int unavailable = 0;
        int splitsWritten = 0;
        List<String> problems = new ArrayList<>();
        boolean stoppedEarly = false;

        for (String symbol : symbols) {
            if (attempted >= properties.dailyRequestBudget()) {
                log.info("stopping refresh at the configured budget of {} requests",
                        properties.dailyRequestBudget());
                stoppedEarly = true;
                break;
            }
            attempted++;
            FeedResult result = feed.fetchSplits(symbol);

            if (result.isOk()) {
                int rows = referenceData.ingestSplits(symbol, result.body(), "fmp");
                succeeded++;
                splitsWritten += rows;
                log.debug("refreshed {} splits for {}", rows, symbol);
                continue;
            }

            referenceData.recordFailure(symbol, result.outcome().name(), result.httpStatus(), result.detail());
            problems.add(symbol + " " + result.outcome() + " (HTTP " + result.httpStatus() + ")");
            if (result.outcome() == FeedOutcome.NOT_AVAILABLE) {
                unavailable++;
            }
            if (result.outcome().isFatalToARun()) {
                log.error("stopping refresh: {} is not a per symbol failure. {}",
                        result.outcome(), result.detail());
                stoppedEarly = true;
                break;
            }
        }

        RefreshReport report = new RefreshReport(
                attempted, succeeded, unavailable, splitsWritten, List.copyOf(problems), stoppedEarly);
        log.info("split refresh: {}", report);
        return report;
    }

    /**
     * What a refresh run did.
     *
     * @param unavailable how many symbols the subscription does not cover. Reported rather
     *     than buried, because on a free tier this is most of them and a run that looks
     *     like it succeeded while checking nothing is worse than one that fails.
     */
    public record RefreshReport(
            int attempted,
            int succeeded,
            int unavailable,
            int splitsWritten,
            List<String> problems,
            boolean stoppedEarly) {

        public RefreshReport {
            problems = List.copyOf(problems);
        }

        public boolean isClean() {
            return problems.isEmpty() && !stoppedEarly;
        }

        @Override
        public String toString() {
            return attempted + " attempted, " + succeeded + " succeeded, " + unavailable
                    + " not available, " + splitsWritten + " splits written"
                    + (stoppedEarly ? ", stopped early" : "")
                    + (problems.isEmpty() ? "" : ". Problems: " + String.join("; ", problems));
        }
    }
}
