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
    /** A charge against the account: a fee billed separately, an ADR fee, a service charge. */
    FEE,
    /** Tax withheld at source, arriving as its own line rather than netted into a dividend. */
    WITHHOLDING,
    /** Cash moving in or out of the account. */
    CASH_TRANSFER,
    /** Securities moving in or out of the account. */
    SECURITY_TRANSFER
}
