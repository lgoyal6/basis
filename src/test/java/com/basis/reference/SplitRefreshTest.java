package com.basis.reference;

import static com.basis.support.Fixtures.AAPL;
import static com.basis.support.Fixtures.JAN_15;
import static org.assertj.core.api.Assertions.assertThat;

import com.basis.persistence.ReferenceDataRepository;
import com.basis.reconcile.CoverageStatus;
import com.basis.reconcile.SplitCoverage;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Ingest and refresh against a real Postgres, driven by canned provider responses.
 *
 * <p>No network. The feed is the seam: everything above it is tested here with bodies
 * captured from the live provider, and the one test that actually calls out lives in
 * {@code FmpLiveContractTest} and is excluded from the default run.
 */
@SpringBootTest
@Testcontainers
class SplitRefreshTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String AAPL_BODY = """
            [{"symbol":"AAPL","date":"2020-08-31","numerator":4,"denominator":1,"splitType":"stock-split"},
             {"symbol":"AAPL","date":"2014-06-09","numerator":7,"denominator":1,"splitType":"stock-split"}]
            """;

    @Autowired
    JdbcClient db;

    @Autowired
    ReferenceDataRepository referenceData;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM reference_data").update();
        db.sql("DELETE FROM reference_data_fetch").update();
        db.sql("DELETE FROM break_record").update();
        db.sql("DELETE FROM position").update();
    }

    @Test
    @DisplayName("a provider response is parsed by Postgres and lands as split rows")
    void ingestsAProviderResponse() {
        int rows = referenceData.ingestSplits("AAPL", AAPL_BODY, "fmp");

        assertThat(rows).isEqualTo(2);
        SplitCoverage coverage = referenceData.coverageBetween(AAPL, LocalDate.of(2000, 1, 1), JAN_15);
        assertThat(coverage.status()).isEqualTo(CoverageStatus.CHECKED);
        assertThat(coverage.splits()).extracting(split -> split.numerator() + ":" + split.denominator())
                .containsExactly("7:1", "4:1");
    }

    @Test
    @DisplayName("two entries for the same date do not kill the whole statement")
    void duplicateDatesInOneResponseAreDeduped() {
        // Without DISTINCT ON, Postgres rejects this outright with "ON CONFLICT DO UPDATE
        // command cannot affect row a second time" and the entire refresh dies on a
        // duplicate the caller never created.
        String duplicated = """
                [{"symbol":"AAPL","date":"2020-08-31","numerator":4,"denominator":1},
                 {"symbol":"AAPL","date":"2020-08-31","numerator":4,"denominator":1}]
                """;

        int rows = referenceData.ingestSplits("AAPL", duplicated, "fmp");

        assertThat(rows).isEqualTo(1);
        assertThat(referenceData.countCached(ReferenceDataRepository.SPLIT)).isEqualTo(1);
    }

    @Test
    @DisplayName("malformed entries are skipped rather than failing the response")
    void incompleteEntriesAreSkipped() {
        String partlyBroken = """
                [{"symbol":"AAPL","date":"2020-08-31","numerator":4,"denominator":1},
                 {"symbol":"AAPL","numerator":2,"denominator":1},
                 {"symbol":"AAPL","date":"2014-06-09"}]
                """;

        assertThat(referenceData.ingestSplits("AAPL", partlyBroken, "fmp")).isEqualTo(1);
    }

    @Test
    @DisplayName("an empty response is a successful check, not a missing one")
    void emptyResponseStillCountsAsChecked() {
        referenceData.ingestSplits("KO", "[]", "fmp");

        assertThat(referenceData.statusFor("KO"))
                .as("the provider answered, so an absence of splits is a fact")
                .isEqualTo(CoverageStatus.CHECKED);
        assertThat(referenceData.coverageBetween(
                com.basis.domain.Commodity.equity("KO"), LocalDate.of(2000, 1, 1), JAN_15).isAuthoritative())
                .isTrue();
    }

    @Test
    @DisplayName("never fetched, could not fetch, and fetched are three different answers")
    void coverageDistinguishesTheThreeCases() {
        assertThat(referenceData.statusFor("MSFT")).isEqualTo(CoverageStatus.NEVER_CHECKED);

        referenceData.recordFailure("MSFT", "NOT_AVAILABLE", 402, "not in your subscription");
        assertThat(referenceData.statusFor("MSFT")).isEqualTo(CoverageStatus.CHECK_FAILED);
        SplitCoverage failed = referenceData.coverageBetween(
                com.basis.domain.Commodity.equity("MSFT"), LocalDate.of(2000, 1, 1), JAN_15);
        assertThat(failed.isAuthoritative()).isFalse();
        assertThat(failed.detail()).contains("NOT_AVAILABLE").contains("402");

        referenceData.ingestSplits("MSFT", "[]", "fmp");
        assertThat(referenceData.statusFor("MSFT")).isEqualTo(CoverageStatus.CHECKED);
    }

    @Test
    @DisplayName("a later failure does not erase the memory of the last good fetch")
    void failureDoesNotForgetTheLastSuccess() {
        referenceData.ingestSplits("AAPL", AAPL_BODY, "fmp");
        var successAt = referenceData.lastSuccessfulFetch("AAPL");

        referenceData.recordFailure("AAPL", "TRANSPORT_ERROR", 0, "timeout");

        assertThat(referenceData.lastSuccessfulFetch("AAPL"))
                .as("yesterday's split history is not made untrustworthy by today's timeout")
                .isEqualTo(successAt);
        assertThat(referenceData.coverageBetween(AAPL, LocalDate.of(2000, 1, 1), JAN_15).isAuthoritative())
                .isTrue();
    }

    @Test
    @DisplayName("refresh spends requests on symbols with an open break before anything else")
    void refreshPrioritisesSymbolsBlockingABreak() {
        db.sql("INSERT INTO position (account, commodity, quantity) VALUES "
                + "('Assets:Broker:IBKR:AAPL','AAPL',10), "
                + "('Assets:Broker:IBKR:MSFT','MSFT',10), "
                + "('Assets:Broker:IBKR:SPY','SPY',10)").update();
        insertOpenBreak("SPY", ProbableCauseCodes.UNAPPLIED_SPLIT);

        List<String> order = referenceData.symbolsNeedingRefresh(Duration.ofDays(7), 10);

        assertThat(order).startsWith("SPY");
        assertThat(order).containsExactlyInAnyOrder("SPY", "AAPL", "MSFT");
    }

    @Test
    @DisplayName("a recently refreshed symbol is not worth another request")
    void freshSymbolsAreSkipped() {
        db.sql("INSERT INTO position (account, commodity, quantity) VALUES "
                + "('Assets:Broker:IBKR:AAPL','AAPL',10)").update();
        referenceData.ingestSplits("AAPL", AAPL_BODY, "fmp");

        assertThat(referenceData.symbolsNeedingRefresh(Duration.ofDays(7), 10)).isEmpty();
        assertThat(referenceData.symbolsNeedingRefresh(Duration.ZERO, 10))
                .as("with no grace period everything is stale again")
                .containsExactly("AAPL");
    }

    @Test
    @DisplayName("a symbol nobody holds and nothing is blocked on is never refreshed")
    void unheldSymbolsAreNotCandidates() {
        assertThat(referenceData.symbolsNeedingRefresh(Duration.ofDays(7), 10)).isEmpty();
    }

    @Test
    @DisplayName("a run stops at the budget rather than spending everything it can")
    void budgetIsRespected() {
        StubFeed feed = new StubFeed().returning("AAPL", AAPL_BODY).returning("MSFT", "[]").returning("SPY", "[]");
        SplitRefreshService service = new SplitRefreshService(feed, referenceData, properties(2));

        var report = service.refresh(List.of("AAPL", "MSFT", "SPY"));

        assertThat(report.attempted()).isEqualTo(2);
        assertThat(report.stoppedEarly()).isTrue();
        assertThat(feed.asked).containsExactly("AAPL", "MSFT");
    }

    @Test
    @DisplayName("a 402 is per symbol and the run keeps going")
    void notAvailableDoesNotStopTheRun() {
        StubFeed feed = new StubFeed()
                .failing("AAPL", FeedOutcome.NOT_AVAILABLE, 402, "not in your subscription")
                .returning("MSFT", AAPL_BODY);
        SplitRefreshService service = new SplitRefreshService(feed, referenceData, properties(10));

        var report = service.refresh(List.of("AAPL", "MSFT"));

        assertThat(report.attempted()).isEqualTo(2);
        assertThat(report.succeeded()).isEqualTo(1);
        assertThat(report.unavailable()).isEqualTo(1);
        assertThat(report.stoppedEarly()).isFalse();
        assertThat(referenceData.statusFor("AAPL")).isEqualTo(CoverageStatus.CHECK_FAILED);
        assertThat(referenceData.statusFor("MSFT")).isEqualTo(CoverageStatus.CHECKED);
    }

    @Test
    @DisplayName("a bad key stops the run instead of failing every symbol the same way")
    void unauthorizedStopsTheRun() {
        StubFeed feed = new StubFeed()
                .failing("AAPL", FeedOutcome.UNAUTHORIZED, 401, "Invalid API KEY")
                .returning("MSFT", AAPL_BODY);
        SplitRefreshService service = new SplitRefreshService(feed, referenceData, properties(10));

        var report = service.refresh(List.of("AAPL", "MSFT"));

        assertThat(report.attempted()).isEqualTo(1);
        assertThat(report.stoppedEarly()).isTrue();
        assertThat(feed.asked).containsExactly("AAPL");
        assertThat(referenceData.statusFor("MSFT"))
                .as("the run stopped, so MSFT was never asked about")
                .isEqualTo(CoverageStatus.NEVER_CHECKED);
    }

    @Test
    @DisplayName("a disabled fetcher touches nothing")
    void disabledDoesNothing() {
        StubFeed feed = new StubFeed().returning("AAPL", AAPL_BODY);
        FmpProperties disabled = new FmpProperties(null, "key", false, null, null, 10);

        var report = new SplitRefreshService(feed, referenceData, disabled).refresh(List.of("AAPL"));

        assertThat(report.attempted()).isZero();
        assertThat(feed.asked).isEmpty();
    }

    private void insertOpenBreak(String symbol, String causeCode) {
        db.sql("""
                INSERT INTO break_record (as_of_date, account, commodity, commodity_class, break_type,
                                          broker_quantity, computed_quantity, probable_cause, cause_code, status)
                VALUES (:date, :account, :symbol, 'EQUITY', 'QUANTITY_MISMATCH', 40, 10, 'x', :code, 'OPEN')
                """)
                .param("date", JAN_15)
                .param("account", "Assets:Broker:IBKR:" + symbol)
                .param("symbol", symbol)
                .param("code", causeCode)
                .update();
    }

    private static FmpProperties properties(int budget) {
        return new FmpProperties(null, "key", true, null, Duration.ofDays(7), budget);
    }

    /** Cause codes the refresh query filters on, kept here so the test states them itself. */
    private static final class ProbableCauseCodes {
        static final String UNAPPLIED_SPLIT = "UNAPPLIED_SPLIT";
    }

    /** A feed with canned answers, so nothing above the seam needs a network. */
    private static final class StubFeed implements SplitFeed {

        private final Map<String, FeedResult> answers = new LinkedHashMap<>();
        private final List<String> asked = new ArrayList<>();

        StubFeed returning(String symbol, String body) {
            answers.put(symbol, FeedResult.ok(body));
            return this;
        }

        StubFeed failing(String symbol, FeedOutcome outcome, int status, String detail) {
            answers.put(symbol, FeedResult.failed(outcome, status, detail));
            return this;
        }

        @Override
        public FeedResult fetchSplits(String symbol) {
            asked.add(symbol);
            return answers.getOrDefault(symbol,
                    FeedResult.failed(FeedOutcome.UNEXPECTED, 0, "no canned answer for " + symbol));
        }
    }
}
