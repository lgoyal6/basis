package com.basis.importer;

/**
 * A seam for stopping an import part way through, so crash safety can be measured rather
 * than argued for.
 *
 * <p>This exists for the fault injection harness and for nothing else. The default
 * implementation does nothing and is the one wired in production, so the cost of it being
 * here is one interface call per transaction written.
 *
 * <p>It is production code on purpose rather than a test subclass reaching into the write
 * loop. A harness that can only interrupt an import it constructed itself proves something
 * about the harness. This interrupts the real loop, after a real append, and leaves the real
 * batch in whatever state the real code leaves it. That is the thing worth knowing.
 */
public interface ImportInterruption {

    /** Interrupts nothing. The implementation wired in production. */
    ImportInterruption NONE = written -> { };

    /**
     * Called after each transaction is appended and before the batch is committed.
     *
     * @param written how many transactions have been appended so far in this batch
     * @throws RuntimeException to abandon the import here, leaving the batch in flight
     *     exactly as a killed process would leave it
     */
    void afterAppend(int written);
}
