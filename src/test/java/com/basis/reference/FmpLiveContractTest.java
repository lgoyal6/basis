package com.basis.reference;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The one test that actually calls the provider.
 *
 * <p>Excluded from every default run. Enable it with {@code -PwithNetwork}, and only with a
 * key in the environment. Its job is not to test basis; everything above the feed is
 * covered without a socket. Its job is to notice when the provider changes shape underneath
 * the assumptions the rest of the suite is built on, which is the one thing a canned
 * response can never tell you.
 *
 * <p>Costs two requests against whatever the daily quota turns out to be, so it is
 * deliberately small.
 */
@Tag("network")
@EnabledIfEnvironmentVariable(named = "FMP_KEY", matches = ".+")
class FmpLiveContractTest {

    private static final String NOT_A_REAL_SYMBOL = "ZZZZNOTREAL";

    private final FmpSplitFeed feed = new FmpSplitFeed(new FmpProperties(
            null, System.getenv("FMP_KEY"), true, Duration.ofSeconds(20), null, 10));

    @Test
    @DisplayName("a known symbol still returns a JSON array of splits with the fields we read")
    void knownSymbolStillReturnsSplits() {
        FeedResult result = feed.fetchSplits("AAPL");

        assertThat(result.isOk())
                .as("provider said: %s", result.detail())
                .isTrue();
        assertThat(result.body())
                .startsWith("[")
                .contains("\"numerator\"")
                .contains("\"denominator\"")
                .contains("\"date\"");
        assertThat(result.body())
                .as("the 4 for 1 split of 2020 is the anchor week 0 validated against")
                .contains("2020-08-31");
    }

    @Test
    @DisplayName("an unsubscribed symbol still answers 402 rather than an empty list")
    void unsubscribedSymbolStillAnswers402() {
        FeedResult result = feed.fetchSplits(NOT_A_REAL_SYMBOL);

        // If this ever starts returning an empty array instead, the three way coverage
        // model gets simpler and "checked, nothing there" becomes the common case.
        assertThat(result.outcome())
                .as("the whole coverage design rests on this not being an empty success")
                .isEqualTo(FeedOutcome.NOT_AVAILABLE);
        assertThat(result.httpStatus()).isEqualTo(402);
    }
}
