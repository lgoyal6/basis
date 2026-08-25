package com.basis.reference;

import com.basis.persistence.ReferenceDataRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Fetches exchange rates on request and writes them to the cache.
 *
 * <p>Separate from the reconciler on purpose, so that reading a rate can never trigger a
 * network call. Everything about when the network is touched stays in one place, and the
 * answer is always "because somebody ran a command".
 */
@Service
public class FxRefreshService {

    private final FmpFxFeed feed;
    private final ReferenceDataRepository referenceData;
    private final Clock clock;

    public FxRefreshService(FmpFxFeed feed, ReferenceDataRepository referenceData, Clock clock) {
        this.feed = feed;
        this.referenceData = referenceData;
        this.clock = clock;
    }

    /** What happened, in the shape the CLI prints. */
    public record RefreshReport(int attempted, int succeeded, int notAvailable, int quotesWritten,
            List<String> unavailable) {

        public boolean isClean() {
            return attempted == succeeded;
        }

        @Override
        public String toString() {
            return attempted + " pair(s) attempted, " + succeeded + " succeeded, "
                    + notAvailable + " not available, " + quotesWritten + " quote(s) written"
                    + (unavailable.isEmpty() ? "" : ": " + String.join(", ", unavailable));
        }
    }

    public RefreshReport refresh(List<String> pairs) {
        return refresh(pairs, LocalDate.now(clock));
    }

    public RefreshReport refresh(List<String> pairs, LocalDate asOf) {
        int succeeded = 0;
        int notAvailable = 0;
        int written = 0;
        List<String> unavailable = new ArrayList<>();

        for (String raw : pairs) {
            String pair = raw.trim().toUpperCase(java.util.Locale.ROOT).replace("/", "");
            if (pair.length() != 6) {
                throw new IllegalArgumentException("a currency pair is six letters, like EURUSD,"
                        + " but got '" + raw + "'");
            }
            FeedResult result = feed.fetchRates(pair, asOf);
            if (result.isOk()) {
                succeeded++;
                written += referenceData.ingestRates(pair, result.body(), "fmp");
                continue;
            }
            // A pair the provider does not cover is a different thing from a failure, and the
            // report keeps them apart so an unsupported currency does not look like an outage.
            if (result.outcome() == FeedOutcome.NOT_AVAILABLE) {
                notAvailable++;
                unavailable.add(pair);
                referenceData.recordFailure(pair, result.outcome().name(), result.httpStatus(), result.detail());
                continue;
            }
            referenceData.recordFailure(pair, result.outcome().name(), result.httpStatus(), result.detail());
            unavailable.add(pair + " (" + result.outcome() + ")");
        }
        return new RefreshReport(pairs.size(), succeeded, notAvailable, written, List.copyOf(unavailable));
    }
}
