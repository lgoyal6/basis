package com.basis.reference;

import java.util.Objects;

/**
 * One provider response.
 *
 * @param body the raw JSON array on success, empty otherwise. Kept verbatim so Postgres
 *     can parse it and so a provider quirk is diagnosable after the fact.
 * @param detail why it failed, in a sentence safe to log. Never contains the request URL,
 *     because the provider takes its key as a query parameter and a logged URL is a
 *     leaked credential.
 */
public record FeedResult(FeedOutcome outcome, int httpStatus, String body, String detail) {

    public FeedResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(detail, "detail");
    }

    public static FeedResult ok(String body) {
        return new FeedResult(FeedOutcome.OK, 200, body, "");
    }

    public static FeedResult failed(FeedOutcome outcome, int httpStatus, String detail) {
        return new FeedResult(outcome, httpStatus, "", detail);
    }

    public boolean isOk() {
        return outcome == FeedOutcome.OK;
    }
}
