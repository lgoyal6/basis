package com.basis.persistence;

import com.basis.domain.Commodity;
import com.basis.reconcile.KnownSplit;
import com.basis.reconcile.SplitCalendar;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The corporate action reference data cache, and the {@link SplitCalendar} reconciliation
 * reads it through.
 *
 * <p>The payload is kept as the provider sent it, and the fields reconciliation needs are
 * pulled out in SQL with {@code payload->>'numerator'} rather than parsed in Java. Storing
 * the provider's own JSON means a change in what basis needs from a split does not require
 * refetching anything, which matters because week 0 established that the free tier caps
 * how many symbols can be refreshed per day.
 *
 * <p>{@code fetched_at} travels with every answer. A break explained by data that was last
 * confirmed six months ago is a weaker claim than one explained by this morning's data, and
 * the person reading the break should be able to tell which they have.
 */
@Repository
public class ReferenceDataRepository implements SplitCalendar {

    /** The {@code event_type} splits are cached under. */
    public static final String SPLIT = "SPLIT";

    private final JdbcClient db;

    public ReferenceDataRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * Caches a split, replacing whatever was there for the same symbol and date.
     *
     * <p>Upsert rather than insert, because refreshing a symbol has to be safe to repeat.
     * The natural key is the event itself, so a second fetch of the same split updates
     * {@code fetched_at} instead of failing or duplicating.
     */
    @Transactional
    public void cacheSplit(String symbol, LocalDate date, long numerator, long denominator, String source) {
        if (numerator <= 0 || denominator <= 0) {
            throw new IllegalArgumentException(
                    "split ratio must be positive, was " + numerator + ":" + denominator);
        }
        db.sql("""
                INSERT INTO reference_data (symbol, event_type, event_date, payload, source, fetched_at)
                VALUES (:symbol, :type, :date, CAST(:payload AS jsonb), :source, now())
                ON CONFLICT (symbol, event_type, event_date) DO UPDATE
                   SET payload = EXCLUDED.payload,
                       source = EXCLUDED.source,
                       fetched_at = now()
                """)
                .param("symbol", symbol)
                .param("type", SPLIT)
                .param("date", date)
                .param("payload", "{\"symbol\":\"" + symbol + "\",\"date\":\"" + date
                        + "\",\"numerator\":" + numerator + ",\"denominator\":" + denominator + "}")
                .param("source", source)
                .update();
    }

    @Override
    public List<KnownSplit> splitsBetween(Commodity commodity, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            return List.of();
        }
        return db.sql("""
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
    }

    /** How stale the cache is for a symbol, so staleness can be reported rather than assumed away. */
    public Instant lastFetched(String symbol) {
        return db.sql("SELECT max(fetched_at) FROM reference_data WHERE symbol = :symbol")
                .param("symbol", symbol)
                .query(Instant.class)
                .optional()
                .orElse(null);
    }

    public int countCached(String eventType) {
        return db.sql("SELECT count(*) FROM reference_data WHERE event_type = :type")
                .param("type", eventType)
                .query(Integer.class)
                .single();
    }

    private static KnownSplit mapSplit(Commodity commodity, ResultSet rs) throws SQLException {
        return new KnownSplit(
                commodity,
                rs.getObject("event_date", LocalDate.class),
                rs.getLong("numerator"),
                rs.getLong("denominator"),
                rs.getObject("fetched_at", java.time.OffsetDateTime.class).toInstant());
    }
}
