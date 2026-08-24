package com.basis.persistence;

import com.basis.domain.Lot;
import com.basis.domain.Quantity;
import com.basis.ledger.LedgerState;
import com.basis.ledger.PositionKey;
import com.basis.ledger.RealizedGain;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and writes the derived tables, and hashes them.
 *
 * <p>Nothing else writes to position, lot or realized_gain. That is what lets
 * {@link #truncate()} be safe, and it is what makes the hash meaningful: if any other
 * component could touch these tables, a matching hash would prove nothing.
 */
@Repository
public class DerivedStateRepository {

    /**
     * Field separator for the canonical dump: ASCII unit separator, which cannot occur
     * in an account name, a commodity symbol or a formatted number. Joining with a
     * separator that could occur would let two different row shapes hash alike.
     */
    private static final String FIELD = String.valueOf((char) 0x1f);

    private final JdbcClient db;

    public DerivedStateRepository(JdbcClient db) {
        this.db = db;
    }

    /** Drops all derived state. Safe at any time: every row here is rebuildable from posting. */
    @Transactional
    public void truncate() {
        db.sql("TRUNCATE TABLE position, lot, realized_gain RESTART IDENTITY").update();
    }

    @Transactional
    public void write(LedgerState state) {
        for (Map.Entry<PositionKey, Quantity> entry : state.positions().entrySet()) {
            db.sql("INSERT INTO position (account, commodity, quantity) VALUES (:account, :commodity, :quantity)")
                    .param("account", entry.getKey().account().name())
                    .param("commodity", entry.getKey().commodity().symbol())
                    .param("quantity", entry.getValue().value())
                    .update();
        }
        for (Lot lot : state.allLots()) {
            db.sql("""
                    INSERT INTO lot (lot_id, account, commodity, acquisition_date, unit_cost,
                                     unit_cost_currency, original_quantity, remaining_quantity)
                    VALUES (:lotId, :account, :commodity, :acquisitionDate, :unitCost,
                            :currency, :original, :remaining)
                    """)
                    .param("lotId", lot.id().value())
                    .param("account", lot.account().name())
                    .param("commodity", lot.commodity().symbol())
                    .param("acquisitionDate", lot.acquisitionDate())
                    .param("unitCost", lot.unitCost().value())
                    .param("currency", lot.unitCost().currency().getCurrencyCode())
                    .param("original", lot.originalQuantity().value())
                    .param("remaining", lot.remainingQuantity().value())
                    .update();
        }
        for (RealizedGain gain : state.realizedGains()) {
            db.sql("""
                    INSERT INTO realized_gain (txn_id, sale_date, account, commodity, quantity,
                                               proceeds_minor, basis_minor, gain_minor, currency)
                    VALUES (:txnId, :saleDate, :account, :commodity, :quantity,
                            :proceeds, :basis, :gain, :currency)
                    """)
                    .param("txnId", gain.txnId().value())
                    .param("saleDate", gain.date())
                    .param("account", gain.account().name())
                    .param("commodity", gain.commodity().symbol())
                    .param("quantity", gain.quantity().value())
                    .param("proceeds", gain.proceeds().minorUnits())
                    .param("basis", gain.basis().minorUnits())
                    .param("gain", gain.gain().minorUnits())
                    .param("currency", gain.gain().currency().getCurrencyCode())
                    .update();
        }
    }

    /**
     * SHA-256 over a canonical dump of every derived table. Invariant 7 compares this
     * before and after a truncate and replay.
     *
     * <p>Three things are deliberate. Rows are sorted in Java rather than by the
     * database, because ORDER BY on text depends on the server's collation and the hash
     * has to be stable across environments and not merely across replays on one machine.
     * Fields are joined with a separator that cannot occur in any of them, so that two
     * different row shapes cannot hash alike. And {@code realized_gain.id} is excluded:
     * it is a sequence that restarts on every rebuild, so hashing it would guarantee a
     * mismatch while proving nothing about the state that matters.
     */
    public String hash() {
        MessageDigest digest = sha256();
        for (String line : canonicalDump()) {
            digest.update(line.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * The exact byte content the hash is taken over. Kept public so that a hash mismatch
     * can be read as a diff rather than as two hex strings that differ.
     */
    public List<String> canonicalDump() {
        List<String> lines = new ArrayList<>();
        lines.add("table:position");
        lines.addAll(sorted(db.sql("SELECT account, commodity, quantity FROM position")
                .query((rs, n) -> join(rs.getString(1), rs.getString(2), rs.getBigDecimal(3).toPlainString()))
                .list()));
        lines.add("table:lot");
        lines.addAll(sorted(db.sql("""
                SELECT lot_id, account, commodity, acquisition_date, unit_cost,
                       unit_cost_currency, original_quantity, remaining_quantity
                  FROM lot
                """)
                .query((rs, n) -> join(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getBigDecimal(5).toPlainString(), rs.getString(6),
                        rs.getBigDecimal(7).toPlainString(), rs.getBigDecimal(8).toPlainString()))
                .list()));
        lines.add("table:realized_gain");
        lines.addAll(sorted(db.sql("""
                SELECT txn_id::text, sale_date, account, commodity, quantity,
                       proceeds_minor, basis_minor, gain_minor, currency
                  FROM realized_gain
                """)
                .query((rs, n) -> join(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getBigDecimal(5).toPlainString(), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9)))
                .list()));
        return lines;
    }

    public int countPositions() {
        return db.sql("SELECT count(*) FROM position").query(Integer.class).single();
    }

    public int countLots() {
        return db.sql("SELECT count(*) FROM lot").query(Integer.class).single();
    }

    public int countRealizedGains() {
        return db.sql("SELECT count(*) FROM realized_gain").query(Integer.class).single();
    }

    private static List<String> sorted(List<String> rows) {
        List<String> copy = new ArrayList<>(rows);
        copy.sort(String::compareTo);
        return copy;
    }

    private static String join(String... fields) {
        return String.join(FIELD, fields);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
