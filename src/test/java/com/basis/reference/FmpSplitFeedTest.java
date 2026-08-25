package com.basis.reference;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How the provider's responses are read, tested against bodies captured from the live
 * provider rather than invented.
 *
 * <p>Two of these would have been got wrong from memory, and both matter: a 402 body is
 * plain text rather than JSON, and an unknown or unsubscribed symbol answers 402 rather
 * than returning an empty array.
 */
class FmpSplitFeedTest {

    private static final String REAL_AAPL_BODY = """
            [
              {"symbol":"AAPL","date":"2020-08-31","numerator":4,"denominator":1,"splitType":"stock-split"},
              {"symbol":"AAPL","date":"2014-06-09","numerator":7,"denominator":1,"splitType":"stock-split"}
            ]
            """;

    // Captured verbatim. Note it is not JSON, so a parser pointed at it would throw.
    private static final String REAL_402_BODY =
            "Premium Query Parameter: 'Special Endpoint : This value set for 'symbol' is not available"
                    + " under your current subscription please visit our subscription page to upgrade your plan";

    private static final String REAL_401_BODY =
            "{  \"Error Message\": \"Invalid API KEY. Feel free to create a Free API Key or visit"
                    + " https://site.financialmodelingprep.com/faqs for more information.\"}";

    @Test
    @DisplayName("a real splits response is passed through verbatim for Postgres to parse")
    void successPassesTheBodyThrough() {
        FeedResult result = FmpSplitFeed.interpret("AAPL", 200, REAL_AAPL_BODY);

        assertThat(result.isOk()).isTrue();
        assertThat(result.outcome()).isEqualTo(FeedOutcome.OK);
        assertThat(result.body()).contains("\"numerator\":4").startsWith("[");
    }

    @Test
    @DisplayName("an empty array is a real answer, not a failure")
    void emptyArrayIsSuccess() {
        FeedResult result = FmpSplitFeed.interpret("KO", 200, "[]");

        assertThat(result.isOk())
                .as("a security with no splits is exactly what reconciliation needs to know")
                .isTrue();
        assertThat(result.body()).isEqualTo("[]");
    }

    @Test
    @DisplayName("402 is per symbol and reads as not available, despite a plain text body")
    void paywalledSymbolIsNotAvailable() {
        FeedResult result = FmpSplitFeed.interpret("ZZZZ", 402, REAL_402_BODY);

        assertThat(result.outcome()).isEqualTo(FeedOutcome.NOT_AVAILABLE);
        assertThat(result.outcome().isFatalToARun())
                .as("most free tier symbols answer this way, so a run must continue past it")
                .isFalse();
        assertThat(result.detail()).contains("not available under your current subscription");
        assertThat(result.body()).isEmpty();
    }

    @Test
    @DisplayName("401 stops a whole run, because it is not about the symbol")
    void badKeyIsFatalToARun() {
        FeedResult result = FmpSplitFeed.interpret("AAPL", 401, REAL_401_BODY);

        assertThat(result.outcome()).isEqualTo(FeedOutcome.UNAUTHORIZED);
        assertThat(result.outcome().isFatalToARun()).isTrue();
    }

    @Test
    @DisplayName("a 200 carrying an error object is a failure wearing a success status")
    void errorObjectAtTwoHundredIsNotSuccess() {
        FeedResult result = FmpSplitFeed.interpret("AAPL", 200, REAL_401_BODY);

        assertThat(result.outcome()).isEqualTo(FeedOutcome.UNEXPECTED);
        assertThat(result.detail()).contains("expected a JSON array");
    }

    @Test
    @DisplayName("other statuses are unexpected rather than silently ignored")
    void serverErrorIsUnexpected() {
        assertThat(FmpSplitFeed.interpret("AAPL", 500, "upstream exploded").outcome())
                .isEqualTo(FeedOutcome.UNEXPECTED);
        assertThat(FmpSplitFeed.interpret("AAPL", 429, "slow down").outcome())
                .isEqualTo(FeedOutcome.UNEXPECTED);
    }

    @Test
    @DisplayName("failure detail is bounded, so one bad response cannot flood a log")
    void detailIsTruncated() {
        FeedResult result = FmpSplitFeed.interpret("AAPL", 500, "x".repeat(5000));

        assertThat(result.detail()).hasSizeLessThan(250).endsWith("...");
    }

    @Test
    @DisplayName("no configured key fails without making a request")
    void missingKeyDoesNotCallOut() {
        FmpProperties properties = new FmpProperties(null, "", true, null, null, 0);
        FeedResult result = new FmpSplitFeed(properties, null).fetchSplits("AAPL");

        assertThat(result.outcome()).isEqualTo(FeedOutcome.UNAUTHORIZED);
        assertThat(result.detail()).contains("no API key");
    }

    @Test
    @DisplayName("the key never appears in anything the feed reports")
    void detailNeverCarriesTheKey() {
        FmpProperties properties = new FmpProperties(null, "supersecretkey", true, null, null, 0);

        assertThat(properties.key()).isEqualTo("supersecretkey");
        assertThat(FmpSplitFeed.interpret("AAPL", 402, REAL_402_BODY).detail())
                .doesNotContain("supersecretkey")
                .doesNotContain("apikey");
    }

    @Test
    @DisplayName("enabling the fetcher without a key is refused at startup, not at request time")
    void enabledWithoutAKeyIsRefused() {
        FmpProperties properties = new FmpProperties(null, "  ", true, null, null, 0);

        org.assertj.core.api.Assertions.assertThatThrownBy(properties::requireUsable)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FMP_KEY")
                .hasMessageContaining("source .env");
    }

    @Test
    @DisplayName("defaults are sane, so a blank configuration still works")
    void defaultsFillThemselvesIn() {
        FmpProperties properties = new FmpProperties(null, null, false, null, null, 0);

        assertThat(properties.baseUrl()).isEqualTo("https://financialmodelingprep.com/stable");
        assertThat(properties.timeout().toSeconds()).isEqualTo(20);
        assertThat(properties.refreshAfter().toDays()).isEqualTo(7);
        assertThat(properties.dailyRequestBudget())
                .as("half of the 250 a day the free plan states")
                .isEqualTo(125);
        assertThat(properties.hasKey()).isFalse();
        properties.requireUsable();
    }
}
