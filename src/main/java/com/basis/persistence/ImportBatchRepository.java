package com.basis.persistence;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The import batch lifecycle, which is the crash recovery story.
 *
 * <p>A batch is opened with a null {@code committed_at}, and stays that way until it
 * either commits or is abandoned. There is no third state and no way to express one.
 */
@Repository
public class ImportBatchRepository {

    private final JdbcClient db;

    public ImportBatchRepository(JdbcClient db) {
        this.db = db;
    }

    /** Opens a batch. Its committed_at is null from here until it commits. */
    public long open(String source, String filename, byte[] contentHash) {
        return db.sql("""
                INSERT INTO import_batch (source, filename, content_hash)
                VALUES (:source, :filename, :contentHash)
                RETURNING id
                """)
                .param("source", source)
                .param("filename", filename)
                .param("contentHash", contentHash)
                .query(Long.class)
                .single();
    }

    /** Marks a batch complete. After this it is no longer a recovery candidate. */
    public void commit(long batchId, int rowCount) {
        int updated = db.sql("""
                UPDATE import_batch
                   SET committed_at = now(), row_count = :rowCount
                 WHERE id = :id AND committed_at IS NULL AND abandoned_at IS NULL
                """)
                .param("rowCount", rowCount)
                .param("id", batchId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException(
                    "import batch " + batchId + " is not in flight and cannot be committed");
        }
    }

    /**
     * Rolls a batch back: its transactions go, and the row stays as a record that it
     * happened. The transactions cascade from the delete on txn, which is why the
     * schema has ON DELETE CASCADE rather than application side cleanup.
     */
    public void abandon(long batchId, String reason) {
        db.sql("DELETE FROM txn WHERE import_batch_id = :id").param("id", batchId).update();
        int updated = db.sql("""
                UPDATE import_batch
                   SET abandoned_at = now(), abandon_reason = :reason
                 WHERE id = :id AND committed_at IS NULL AND abandoned_at IS NULL
                """)
                .param("reason", reason)
                .param("id", batchId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException(
                    "import batch " + batchId + " is not in flight and cannot be abandoned");
        }
    }

    /**
     * Batches that were in flight when the process stopped, oldest first.
     *
     * <p>This is the query that makes {@code committed_at} a crash marker rather than
     * just a timestamp.
     */
    public List<Long> findInFlight() {
        return db.sql("""
                SELECT id FROM import_batch
                 WHERE committed_at IS NULL AND abandoned_at IS NULL
                 ORDER BY started_at, id
                """)
                .query(Long.class)
                .list();
    }

    public boolean isCommitted(long batchId) {
        return db.sql("SELECT committed_at IS NOT NULL FROM import_batch WHERE id = :id")
                .param("id", batchId)
                .query(Boolean.class)
                .single();
    }

    public int countTransactions(long batchId) {
        return db.sql("SELECT count(*) FROM txn WHERE import_batch_id = :id")
                .param("id", batchId)
                .query(Integer.class)
                .single();
    }
}
