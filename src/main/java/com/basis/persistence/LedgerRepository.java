package com.basis.persistence;

import com.basis.domain.Account;
import com.basis.domain.Commodity;
import com.basis.domain.CommodityClass;
import com.basis.domain.Cost;
import com.basis.domain.LotId;
import com.basis.domain.Posting;
import com.basis.domain.Price;
import com.basis.domain.Quantity;
import com.basis.domain.Transaction;
import com.basis.domain.TxnId;
import com.basis.ledger.BalanceChecker;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Currency;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes transactions and reads postings back.
 *
 * <p>Every append runs the balance check first. There is no path into this table that
 * has not been through {@link BalanceChecker}, which is the point of doing it here
 * rather than trusting every caller.
 */
@Repository
public class LedgerRepository {

    private final JdbcClient db;

    public LedgerRepository(JdbcClient db) {
        this.db = db;
    }

    /**
     * Appends a transaction and its postings, in posting order.
     *
     * @return true if it was written, false if this idempotency key was already present,
     *     which is what makes re importing a statement a no op
     */
    @Transactional
    public boolean append(long importBatchId, Transaction transaction) {
        BalanceChecker.requireBalanced(transaction);
        int inserted = db.sql("""
                INSERT INTO txn (id, import_batch_id, txn_date, event_type, narration,
                                 idempotency_key, source_row)
                VALUES (:id, :batchId, :date, :eventType, :narration,
                        :key, CAST(:sourceRow AS jsonb))
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
                .param("id", transaction.id().value())
                .param("batchId", importBatchId)
                .param("date", transaction.date())
                .param("eventType", transaction.eventType())
                .param("narration", transaction.narration())
                .param("key", transaction.idempotencyKey().bytes())
                .param("sourceRow", transaction.sourceRow())
                .update();
        if (inserted == 0) {
            return false;
        }
        List<Posting> postings = transaction.postings();
        for (int ordinal = 0; ordinal < postings.size(); ordinal++) {
            insertPosting(transaction.id(), ordinal, postings.get(ordinal));
        }
        return true;
    }

    private void insertPosting(TxnId txnId, int ordinal, Posting posting) {
        Cost cost = posting.cost();
        db.sql("""
                INSERT INTO posting (txn_id, ordinal, account, commodity, commodity_class, quantity,
                                     cost_unit_amount, cost_currency, cost_date, lot_id,
                                     weight_minor, weight_currency)
                VALUES (:txnId, :ordinal, :account, :commodity, :commodityClass, :quantity,
                        :costAmount, :costCurrency, :costDate, :lotId,
                        :weightMinor, :weightCurrency)
                """)
                .param("txnId", txnId.value())
                .param("ordinal", ordinal)
                .param("account", posting.account().name())
                .param("commodity", posting.commodity().symbol())
                .param("commodityClass", posting.commodity().commodityClass().name())
                .param("quantity", posting.quantity().value())
                .param("costAmount", cost == null ? null : cost.unitCost().value())
                .param("costCurrency", cost == null ? null : cost.unitCost().currency().getCurrencyCode())
                .param("costDate", cost == null ? null : cost.acquisitionDate())
                .param("lotId", cost == null ? null : cost.lotId().value())
                .param("weightMinor", posting.weight().minorUnits())
                .param("weightCurrency", posting.weight().currency().getCurrencyCode())
                .update();
    }

    /**
     * Every posting in replay order.
     *
     * <p>Ordered by {@code posting.id}, which is the order they were written. That
     * ordering is the definition of replay, so it is spelled out here rather than left
     * to the planner.
     */
    public List<PostingRow> readAllInReplayOrder() {
        return db.sql("""
                SELECT p.id, p.txn_id, t.txn_date, p.ordinal, p.account, p.commodity,
                       p.commodity_class, p.quantity, p.cost_unit_amount, p.cost_currency,
                       p.cost_date, p.lot_id, p.weight_minor
                  FROM posting p
                  JOIN txn t ON t.id = p.txn_id
                 ORDER BY p.id
                """)
                .query(LedgerRepository::mapPostingRow)
                .list();
    }

    public int countTransactions() {
        return db.sql("SELECT count(*) FROM txn").query(Integer.class).single();
    }

    /**
     * Transactions whose postings do not sum to zero at cost, asked of the database
     * rather than of the application. Should always be empty.
     */
    public List<String> findUnbalancedTransactions() {
        return db.sql("""
                SELECT txn_id::text || ' ' || weight_currency || ' ' || sum(weight_minor)::text
                  FROM posting
                 GROUP BY txn_id, weight_currency
                HAVING sum(weight_minor) <> 0
                 ORDER BY 1
                """)
                .query(String.class)
                .list();
    }

    private static PostingRow mapPostingRow(ResultSet rs, int rowNumber) throws SQLException {
        Commodity commodity = new Commodity(
                rs.getString("commodity"), CommodityClass.valueOf(rs.getString("commodity_class")));
        String lotId = rs.getString("lot_id");
        Cost cost = lotId == null ? null : new Cost(
                LotId.of(lotId),
                Price.of(rs.getBigDecimal("cost_unit_amount"), Currency.getInstance(rs.getString("cost_currency"))),
                rs.getObject("cost_date", java.time.LocalDate.class));
        Posting posting = new Posting(
                Account.of(rs.getString("account")),
                commodity,
                Quantity.of(rs.getBigDecimal("quantity")),
                cost);
        return new PostingRow(
                rs.getLong("id"),
                TxnId.of(rs.getObject("txn_id", java.util.UUID.class)),
                rs.getObject("txn_date", java.time.LocalDate.class),
                rs.getInt("ordinal"),
                posting,
                rs.getLong("weight_minor"));
    }
}
