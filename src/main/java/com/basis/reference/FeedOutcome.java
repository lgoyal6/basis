package com.basis.reference;

/**
 * What happened when the provider was asked for a symbol.
 *
 * <p>The values are the ones the provider actually produces, established by probing it
 * rather than assumed. On the free tier a symbol outside the subscription answers 402,
 * so {@link #NOT_AVAILABLE} is the ordinary case and not the exceptional one.
 */
public enum FeedOutcome {
    /** The provider answered with data. An empty list here is a real answer. */
    OK,
    /**
     * HTTP 402. The endpoint or the symbol is outside the current subscription. Per symbol
     * and expected, so it must not stop a refresh run.
     */
    NOT_AVAILABLE,
    /**
     * HTTP 401. The key is wrong or missing. Not a per symbol problem, so a refresh run
     * should stop rather than march through every symbol collecting the same failure.
     */
    UNAUTHORIZED,
    /** The request never completed: timeout, DNS, connection reset. Worth retrying later. */
    TRANSPORT_ERROR,
    /** Anything else, including a 200 whose body is not the array that was expected. */
    UNEXPECTED;

    /** True when the whole run should stop, rather than moving on to the next symbol. */
    public boolean isFatalToARun() {
        return this == UNAUTHORIZED;
    }
}
