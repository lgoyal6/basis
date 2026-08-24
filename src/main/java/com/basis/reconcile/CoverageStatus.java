package com.basis.reconcile;

/**
 * How much the reference data actually knows about a symbol.
 *
 * <p>Without this, an empty list of splits means three different things at once, and the
 * one sentence a reader gets is wrong for two of them.
 */
public enum CoverageStatus {
    /** The provider answered. An empty split list here is a fact, not an absence of data. */
    CHECKED,
    /** Nobody has asked the provider about this symbol yet. */
    NEVER_CHECKED,
    /**
     * The provider was asked and refused. On the free tier this is the ordinary case for a
     * symbol outside the subscription, which answers HTTP 402 rather than an empty list.
     */
    CHECK_FAILED
}
