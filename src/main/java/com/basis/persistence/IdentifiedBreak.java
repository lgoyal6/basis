package com.basis.persistence;

import com.basis.reconcile.BreakRecord;

/**
 * A break together with the id the database gave it.
 *
 * <p>{@link BreakRecord} deliberately carries no id: it is what the reconciler computed, and
 * the reconciler runs before anything is stored and produces the same answer whether or not
 * it ever is. The id belongs to the row, not to the finding.
 *
 * <p>But every command that acts on a break takes one: {@code apply break} and {@code settle}
 * both need it, and until this existed the only way to learn a break's id was to query the
 * database by hand. This pairs the two at the boundary where both are known, without putting
 * a storage concern inside the value the reconciler returns.
 */
public record IdentifiedBreak(long id, BreakRecord record) {
}
