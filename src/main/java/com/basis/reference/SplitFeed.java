package com.basis.reference;

/**
 * Somewhere to ask about a security's split history.
 *
 * <p>Narrow on purpose: one method, taking a symbol and returning the provider's raw body.
 * Nothing above this interface knows about HTTP, and nothing below it knows about the
 * ledger. That is what lets the refresh logic be tested against canned responses instead
 * of against the network, and it is why the parsing happens in Postgres rather than here.
 */
public interface SplitFeed {

    /** Never throws for a provider level failure. A failure is a {@link FeedResult}, not an exception. */
    FeedResult fetchSplits(String symbol);
}
