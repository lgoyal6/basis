package com.basis.reconcile;

/** What kind of disagreement a break records. */
public enum BreakType {
    /** Both sides hold the commodity, in different amounts. */
    QUANTITY_MISMATCH,
    /** Both sides agree on the quantity and disagree on what it cost. */
    BASIS_MISMATCH,
    /** The broker reports a position the ledger has never heard of. */
    UNKNOWN_TO_LEDGER,
    /** The ledger holds a position the broker does not report. */
    UNKNOWN_TO_BROKER
}
