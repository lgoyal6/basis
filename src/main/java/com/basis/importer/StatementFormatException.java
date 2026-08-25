package com.basis.importer;

/**
 * A statement line could not be understood.
 *
 * <p>Always fatal to the import that raised it. The tempting alternative, skipping the row
 * and carrying on, produces a ledger that is quietly missing a transaction, and that
 * surfaces later as a break with a confidently wrong cause attached. An import that stops
 * and names the line is a worse afternoon and a better outcome.
 */
public class StatementFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StatementFormatException(String message) {
        super(message);
    }
}
