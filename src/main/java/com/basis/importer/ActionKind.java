package com.basis.importer;

/**
 * What a statement row does to the ledger, independent of who wrote the statement.
 *
 * <p>The vocabulary every broker profile maps its own wording onto. Fidelity writes
 * "YOU BOUGHT", Schwab writes "Buy", another writes "B", and all three mean {@link #BUY}.
 * Keeping that vocabulary here rather than in any one broker's file is what makes a second
 * broker a config file instead of a second copy of the event logic.
 */
public enum ActionKind {
    BUY,
    SELL,
    /** A dividend paid in cash. */
    CASH_DIVIDEND,
    /** A dividend immediately reinvested, which is a distribution followed by a purchase. */
    REINVESTMENT,
    /**
     * Interest credited to the account.
     *
     * <p>Distinct from {@link #CASH_DIVIDEND} because it is a different kind of income from
     * a different kind of source, and folding it into dividends would put it under a heading
     * a tax question would find it in wrongly. A cash sweep fund that pays out as a dividend
     * on a symbol is a dividend and belongs there; interest on a bare balance is this.
     */
    INTEREST,
    /** A charge against the account: a fee billed separately, an ADR fee, a service charge. */
    FEE,
    /** Tax withheld at source, arriving as its own line rather than netted into a dividend. */
    WITHHOLDING,
    /** Cash moving in or out of the account. */
    CASH_TRANSFER,
    /** Securities moving in or out of the account. */
    SECURITY_TRANSFER,
    /**
     * A row that carries no ledger effect and is deliberately skipped.
     *
     * <p>Statements contain informational lines: a running balance, a corporate action
     * notice whose real effect arrives on other rows, a header repeated mid file. Those have
     * to go somewhere, and the choice is between skipping them silently and skipping them on
     * purpose.
     *
     * <p>This is on purpose. A phrase only lands here because someone put it in a broker
     * profile, and the import reports how many rows it ignored, so the count is visible even
     * when the rows are not. Silently dropping an unrecognised row is the failure this whole
     * importer is shaped to avoid; declaring that a row means nothing is a different thing.
     */
    IGNORE
}
