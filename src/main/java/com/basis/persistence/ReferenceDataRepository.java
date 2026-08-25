package com.basis.persistence;

import com.basis.domain.Commodity;
import com.basis.reconcile.CoverageStatus;
import com.basis.reconcile.KnownSplit;
import com.basis.reconcile.SplitCalendar;
import com.basis.reconcile.SplitCoverage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The corporate action reference data cache, and the {@link SplitCalendar} reconciliation
 * reads it through.
 *
 * <p>The provider's JSON is stored as it arrived and the fields reconciliation needs are
 * pulled out in SQL with {@code payload->>'numerator'}. That avoids a JSON parsing
 * dependency, and more usefully it means changing what basis wants from a split does not
 * require refetching anything. Week 0 established that the free tier caps how many symbols
 * can be refreshed per day, so refetching is the expensive operation to design against.
 *
 * <p>Fetches are recorded separately from their results, in {@code reference_data_fetch}.
 * A symbol with no splits and a symbol nobody has fetched both have zero rows here, and
 * telling them apart is the difference between "a corporate action is not the explanation"
 * and "nobody has looked yet".
 */
@Repository
public class ReferenceDataRepository implements SplitCalendar {

    /** The {@code event_type} splits are cached under. */
    public static final String SPLIT = "SPLIT";

    /**
     * Daily exchange rates, in the same table as split history and for the same reason: it is
     * a public fact about the world, cached so it is fetched once and auditable afterwards.
     * The event_date is the date the rate is for, and the symbol is the pair.
     */
    public static final String FX = "FX";

    private final JdbcClient db;

    public ReferenceDataRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * Ingests a provider response for one symbol: the split rows, and the fact of the
     * fetch, in one transaction.
     *
     * <p>Postgres parses the array. {@code DISTINCT ON} is load bearing rather than
     * decorative: if a response carries two entries for the same date, an
     * {@code ON CONFLICT DO UPDATE} touching the same key twice in one statement fails
     * outright with "cannot affect row a second time", and the whole refresh dies on a
     * duplicate the caller did not create.
     *
     * @param body the provider's JSON array, verbatim
     * @return how many split rows were written
     */
    @Transactional
    public int ingestSplits(String symbol, String body, String source) {
        int rows = db.sql("""
                INSERT INTO reference_data (symbol, event_type, event_date, payload, source, fetched_at)
                SELECT DISTINCT ON ((element->>'date')::date)
                       :symbol, :type, (element->>'date')::date, element, :source, now()
                  FROM jsonb_array_elements(CAST(:body AS jsonb)) AS element
                 WHERE element->>'date' IS NOT NULL
                   AND element->>'numerator' IS NOT NULL
                   AND element->>'denominator' IS NOT NULL
                 ORDER BY (element->>'date')::date
                ON CONFLICT (symbol, event_type, event_date) DO UPDATE
                   SET payload = EXCLUDED.payload,
                       source = EXCLUDED.source,
                       fetched_at = now()
                """)
                .param("symbol", symbol)
                .param("type", SPLIT)
                .param("source", source)
                .param("body", body)
                .update();
        recordSuccess(symbol, rows);
        return rows;
    }

    /**
     * Caches a single split directly, without going through a provider response. Used by
     * tests and by the hand maintained corrections a provider gets wrong.
     */
    @Transactional
    public void cacheSplit(String symbol, LocalDate date, long numerator, long denominator, String source) {
        if (numerator <= 0 || denominator <= 0) {
            throw new IllegalArgumentException(
                    "split ratio must be positive, was " + numerator + ":" + denominator);
        }
        ingestSplits(symbol, "[{\"symbol\":\"" + symbol + "\",\"date\":\"" + date
                + "\",\"numerator\":" + numerator + ",\"denominator\":" + denominator + "}]", source);
    }

    /** Records that a fetch succeeded, whatever it returned. Zero rows is a real answer. */
    @Transactional
    public void recordSuccess(String symbol, int rowsReturned) {
        db.sql("""
                INSERT INTO reference_data_fetch (symbol, event_type, last_attempt_at, last_success_at,
                                                  last_outcome, last_status, last_detail, rows_returned)
                VALUES (:symbol, :type, now(), now(), 'OK', 200, NULL, :rows)
                ON CONFLICT (symbol, event_type) DO UPDATE
                   SET last_attempt_at = now(),
                       last_success_at = now(),
                       last_outcome = 'OK',
                       last_status = 200,
                       last_detail = NULL,
                       rows_returned = EXCLUDED.rows_returned,
                       attempts = reference_data_fetch.attempts + 1
                """)
                .param("symbol", symbol)
                .param("type", SPLIT)
                .param("rows", rowsReturned)
                .update();
    }

    /**
     * Records that a fetch failed, without disturbing the memory of the last good one.
     * A 402 today does not make yesterday's split history untrustworthy.
     */
    @Transactional
    public void recordFailure(String symbol, String outcome, Integer httpStatus, String detail) {
        db.sql("""
                INSERT INTO reference_data_fetch (symbol, event_type, last_attempt_at, last_success_at,
                                                  last_outcome, last_status, last_detail, rows_returned)
                VALUES (:symbol, :type, now(), NULL, :outcome, :status, :detail, NULL)
                ON CONFLICT (symbol, event_type) DO UPDATE
                   SET last_attempt_at = now(),
                       last_outcome = EXCLUDED.last_outcome,
                       last_status = EXCLUDED.last_status,
                       last_detail = EXCLUDED.last_detail,
                       rows_returned = NULL,
                       attempts = reference_data_fetch.attempts + 1
                """)
                .param("symbol", symbol)
                .param("type", SPLIT)
                .param("outcome", outcome)
                .param("status", httpStatus)
                .param("detail", detail)
                .update();
    }

    @Override
    public SplitCoverage coverageBetween(Commodity commodity, LocalDate from, LocalDate to) {
        Optional<FetchState> state = fetchState(commodity.symbol());
        if (state.isEmpty() || state.get().lastSuccessAt() == null) {
            return state.map(failed -> SplitCoverage.checkFailed(failed.describe(), failed.lastAttemptAt()))
                    .orElseGet(SplitCoverage::neverChecked);
        }
        if (from.isAfter(to)) {
            return SplitCoverage.checked(List.of(), state.get().lastSuccessAt());
        }
        List<KnownSplit> splits = db.sql("""
                SELECT event_date,
                       (payload->>'numerator')::bigint   AS numerator,
                       (payload->>'denominator')::bigint AS denominator,
                       fetched_at
                  FROM reference_data
                 WHERE symbol = :symbol
                   AND event_type = :type
                   AND event_date BETWEEN :from AND :to
                 ORDER BY event_date
                """)
                .param("symbol", commodity.symbol())
                .param("type", SPLIT)
                .param("from", from)
                .param("to", to)
                .query((ResultSet rs, int row) -> mapSplit(commodity, rs))
                .list();
        return SplitCoverage.checked(splits, state.get().lastSuccessAt());
    }

    /**
     * Symbols whose split history is missing or stale, worst first.
     *
     * <p>Ordered by value rather than alphabetically. A symbol with an open break that a
     * split would explain is worth a request; a symbol nobody holds is not. Never fetched
     * comes before long ago, which comes before recently, and within each the ones with
     * open ratio shaped breaks come first.
     */
    public List<String> symbolsNeedingRefresh(Duration refreshAfter, int limit) {
        return db.sql("""
                WITH candidate AS (
                    SELECT DISTINCT commodity AS symbol
                      FROM break_record
                     WHERE status = 'OPEN'
                       AND cause_code IN ('UNAPPLIED_SPLIT', 'UNAPPLIED_REVERSE_SPLIT', 'RATIO_WITHOUT_KNOWN_SPLIT')
                     UNION
                    SELECT DISTINCT commodity FROM position
                ),
                ranked AS (
                    SELECT c.symbol,
                           f.last_success_at,
                           EXISTS (
                               SELECT 1 FROM break_record b
                                WHERE b.status = 'OPEN'
                                  AND b.commodity = c.symbol
                                  AND b.cause_code IN ('UNAPPLIED_SPLIT', 'UNAPPLIED_REVERSE_SPLIT',
                                                       'RATIO_WITHOUT_KNOWN_SPLIT')
                           ) AS blocks_a_break
                      FROM candidate c
                      LEFT JOIN reference_data_fetch f
                             ON f.symbol = c.symbol AND f.event_type = :type
                     WHERE f.last_success_at IS NULL
                        OR f.last_success_at < now() - CAST(:refreshAfter AS interval)
                )
                SELECT symbol FROM ranked
                 ORDER BY blocks_a_break DESC, last_success_at ASC NULLS FIRST, symbol
                 LIMIT :limit
                """)
                .param("type", SPLIT)
                .param("refreshAfter", refreshAfter.toSeconds() + " seconds")
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    /** When the provider last answered for this symbol, or null if it never has. */
    public Instant lastSuccessfulFetch(String symbol) {
        return fetchState(symbol).map(FetchState::lastSuccessAt).orElse(null);
    }

    public int countCached(String eventType) {
        return db.sql("SELECT count(*) FROM reference_data WHERE event_type = :type")
                .param("type", eventType)
                .query(Integer.class)
                .single();
    }

    private Optional<FetchState> fetchState(String symbol) {
        return db.sql("""
                SELECT last_attempt_at, last_success_at, last_outcome, last_status, last_detail
                  FROM reference_data_fetch
                 WHERE symbol = :symbol AND event_type = :type
                """)
                .param("symbol", symbol)
                .param("type", SPLIT)
                .query((ResultSet rs, int row) -> new FetchState(
                        instant(rs, "last_attempt_at"),
                        instant(rs, "last_success_at"),
                        rs.getString("last_outcome"),
                        (Integer) rs.getObject("last_status"),
                        rs.getString("last_detail")))
                .optional();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static KnownSplit mapSplit(Commodity commodity, ResultSet rs) throws SQLException {
        return new KnownSplit(
                commodity,
                rs.getObject("event_date", LocalDate.class),
                rs.getLong("numerator"),
                rs.getLong("denominator"),
                instant(rs, "fetched_at"));
    }

    /** The last thing that happened when someone asked the provider about a symbol. */
    private record FetchState(
            Instant lastAttemptAt, Instant lastSuccessAt, String outcome, Integer status, String detail) {

        String describe() {
            String reason = detail == null || detail.isBlank() ? outcome : outcome + ": " + detail;
            return status == null ? reason : reason + " [HTTP " + status + "]";
        }
    }

    /** Every symbol the cache has ever answered for, for reporting staleness. */
    public List<String> coveredSymbols() {
        return db.sql("""
                SELECT symbol FROM reference_data_fetch
                 WHERE event_type = :type AND last_success_at IS NOT NULL
                 ORDER BY symbol
                """)
                .param("type", SPLIT)
                .query(String.class)
                .list();
    }

    /** Fetches that are currently in a failed state, for reporting what could not be checked. */
    public List<String> unavailableSymbols() {
        return db.sql("""
                SELECT symbol || ' ' || last_outcome FROM reference_data_fetch
                 WHERE event_type = :type AND last_outcome <> 'OK'
                 ORDER BY symbol
                """)
                .param("type", SPLIT)
                .query(String.class)
                .list();
    }

    /** Exposed so a caller can report staleness without reaching for the enum. */
    public CoverageStatus statusFor(String symbol) {
        return fetchState(symbol)
                .map(state -> state.lastSuccessAt() == null ? CoverageStatus.CHECK_FAILED : CoverageStatus.CHECKED)
                .orElse(CoverageStatus.NEVER_CHECKED);
    }

    /**
     * Stores the provider's daily quotes for one pair.
     *
     * <p>Only the close is kept. A cost basis comparison needs one rate for a day, and keeping
     * four would mean later deciding which of them the comparison meant, which is a decision
     * better made once and written down: the close is the day's settled answer and the one a
     * broker is most likely to have used.
     */
    @Transactional
    public int ingestRates(String pair, String body, String source) {
        int rows = db.sql("""
                INSERT INTO reference_data (symbol, event_type, event_date, payload, source, fetched_at)
                SELECT DISTINCT ON ((element->>'date')::date)
                       :pair, :type, (element->>'date')::date, element, :source, now()
                  FROM jsonb_array_elements(CAST(:body AS jsonb)) AS element
                 WHERE element->>'date' IS NOT NULL
                   AND element->>'close' IS NOT NULL
                 ORDER BY (element->>'date')::date
                ON CONFLICT (symbol, event_type, event_date) DO UPDATE
                   SET payload = EXCLUDED.payload,
                       source = EXCLUDED.source,
                       fetched_at = now()
                """)
                .param("pair", pair)
                .param("type", FX)
                .param("source", source)
                .param("body", body)
                .update();
        recordSuccess(pair, rows);
        return rows;
    }

    /**
     * The most recent quote at or before a date, which is what a weekend needs.
     *
     * <p>Returns the date the rate is really from rather than the date asked for, so a caller
     * can say so. A rate quietly relabelled with the wrong date is the kind of small lie that
     * makes a reconciliation impossible to check by hand.
     */
    public java.util.Optional<com.basis.reconcile.ExchangeRates.Quote> rateAt(
            String pair, java.time.LocalDate on) {
        return db.sql("""
                SELECT event_date, payload->>'close' AS close, source
                  FROM reference_data
                 WHERE symbol = :pair AND event_type = :type AND event_date <= :on
                 ORDER BY event_date DESC
                 LIMIT 1
                """)
                .param("pair", pair)
                .param("type", FX)
                .param("on", on)
                .query((rs, rowNum) -> new com.basis.reconcile.ExchangeRates.Quote(
                        new java.math.BigDecimal(rs.getString("close")),
                        rs.getObject("event_date", java.time.LocalDate.class),
                        rs.getString("source")))
                .optional();
    }
}
