package com.basis.reconcile;

/**
 * Where a break is in its life. Mirrors the {@code break_record_status} constraint,
 * because a status the database refuses is not a status.
 */
public enum BreakStatus {
    OPEN,
    /** A human agreed with the probable cause and the fix has been applied. */
    ACCEPTED,
    /** A human disagreed with the probable cause. The break stands, the explanation does not. */
    REJECTED,
    /** Settled some other way, for example the broker corrected its own statement. */
    RESOLVED
}
