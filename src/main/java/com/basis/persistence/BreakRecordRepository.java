package com.basis.persistence;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.CommodityClass;
import com.basis.domain.Money;
import com.basis.domain.Quantity;
import com.basis.reconcile.BreakRecord;
import com.basis.reconcile.BreakStatus;
import com.basis.reconcile.BreakType;
import com.basis.reconcile.ProbableCause;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores breaks and the judgements people make about them.
 *
 * <p>The one table in the reconciliation path that is not derived state. A break carries a
 * human's decision once it has been triaged, and that decision cannot be recomputed from
 * the posting table, so this survives a truncate and replay while {@code position} and
 * {@code lot} do not.
 */
@Repository
public class BreakRecordRepository {

    private final JdbcClient db;

    public BreakRecordRepository(JdbcClient db) {
        this.db = db;
    }

    /** @return the id of the stored break */
    @Transactional
    public long record(BreakRecord found) {
        Money brokerAmount = found.brokerAmount();
        Money computedAmount = found.computedAmount();
        Currency currency = brokerAmount != null ? brokerAmount.currency()
                : computedAmount != null ? computedAmount.currency() : null;

        return db.sql("""
                INSERT INTO break_record (as_of_date, account, commodity, commodity_class, break_type,
                                          broker_quantity, computed_quantity,
                                          broker_amount_minor, computed_amount_minor, currency,
                                          probable_cause, cause_code, cause_confident, suggested_action,
                                          status)
                VALUES (:asOf, :account, :commodity, :commodityClass, :type,
                        :brokerQuantity, :computedQuantity,
                        :brokerAmount, :computedAmount, :currency,
                        :explanation, :code, :confident, :action,
                        :status)
                RETURNING id
                """)
                .param("asOf", found.asOf())
                .param("account", found.account().name())
                .param("commodity", found.commodity().symbol())
                .param("commodityClass", found.commodity().commodityClass().name())
                .param("type", found.type().name())
                .param("brokerQuantity", found.brokerQuantity().value())
                .param("computedQuantity", found.computedQuantity().value())
                .param("brokerAmount", brokerAmount == null ? null : brokerAmount.minorUnits())
                .param("computedAmount", computedAmount == null ? null : computedAmount.minorUnits())
                .param("currency", currency == null ? null : currency.getCurrencyCode())
                .param("explanation", found.cause().explanation())
                .param("code", found.cause().code())
                .param("confident", found.cause().confident())
                .param("action", found.cause().suggestedAction())
                .param("status", found.status().name())
                .query(Long.class)
                .single();
    }

    @Transactional
    public List<Long> recordAll(List<BreakRecord> found) {
        return found.stream().map(this::record).toList();
    }

    /**
     * Settles a break with a human's judgement.
     *
     * <p>The schema will not let a break leave OPEN without a resolution timestamp, which
     * is why the timestamp is set here rather than left to the caller to remember.
     */
    @Transactional
    public void settle(long id, BreakStatus status, String note) {
        if (status == BreakStatus.OPEN) {
            throw new IllegalArgumentException("settling a break means moving it out of OPEN, not into it");
        }
        int updated = db.sql("""
                UPDATE break_record
                   SET status = :status, resolved_at = now(), resolution_note = :note
                 WHERE id = :id AND status = 'OPEN'
                """)
                .param("status", status.name())
                .param("note", note)
                .param("id", id)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("break " + id + " is not open and cannot be settled again");
        }
    }

    public List<BreakRecord> findOpen(Account account) {
        return db.sql("""
                SELECT as_of_date, account, commodity, commodity_class, break_type,
                       broker_quantity, computed_quantity,
                       broker_amount_minor, computed_amount_minor, currency,
                       probable_cause, cause_code, cause_confident, suggested_action, status
                  FROM break_record
                 WHERE status = 'OPEN' AND (account = :account OR account LIKE :prefix)
                 ORDER BY as_of_date, account, commodity
                """)
                .param("account", account.name())
                .param("prefix", account.name() + ":%")
                .query(BreakRecordRepository::mapBreak)
                .list();
    }

    public int countOpen() {
        return db.sql("SELECT count(*) FROM break_record WHERE status = 'OPEN'")
                .query(Integer.class)
                .single();
    }

    /** How often each kind of explanation comes up, and how often it was corroborated. */
    public List<String> causeSummary() {
        return db.sql("""
                SELECT cause_code || ' confident=' || cause_confident || ' count=' || count(*)
                  FROM break_record
                 GROUP BY cause_code, cause_confident
                 ORDER BY 1
                """)
                .query(String.class)
                .list();
    }

    private static BreakRecord mapBreak(ResultSet rs, int rowNumber) throws SQLException {
        String currencyCode = rs.getString("currency");
        Currency currency = currencyCode == null ? null : Currency.getInstance(currencyCode.trim());
        return new BreakRecord(
                rs.getObject("as_of_date", LocalDate.class),
                Account.of(rs.getString("account")),
                new Commodity(rs.getString("commodity"),
                        CommodityClass.valueOf(rs.getString("commodity_class"))),
                BreakType.valueOf(rs.getString("break_type")),
                quantity(rs, "broker_quantity"),
                quantity(rs, "computed_quantity"),
                money(rs, "broker_amount_minor", currency),
                money(rs, "computed_amount_minor", currency),
                new ProbableCause(rs.getString("cause_code"), rs.getString("probable_cause"),
                        rs.getString("suggested_action"), rs.getBoolean("cause_confident")),
                BreakStatus.valueOf(rs.getString("status")));
    }

    private static Quantity quantity(ResultSet rs, String column) throws SQLException {
        java.math.BigDecimal value = rs.getBigDecimal(column);
        return value == null ? Quantity.ZERO : Quantity.of(value);
    }

    private static Money money(ResultSet rs, String column, Currency currency) throws SQLException {
        long minorUnits = rs.getLong(column);
        return rs.wasNull() || currency == null ? null : Money.ofMinor(minorUnits, currency);
    }
}
