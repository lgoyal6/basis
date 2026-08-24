package com.basis.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A set of postings that happened together on one date.
 *
 * <p>The constructor validates structure only: at least two postings, no nulls, and a
 * source row that will be kept verbatim forever. It deliberately does not enforce the
 * balance invariant, so that {@code BalanceChecker} has something to check and the
 * property test has something it can falsify. Nothing reaches the database without
 * passing the checker. See docs/ARCHITECTURE.md section 12.
 *
 * @param sourceRow the original imported row, verbatim, as JSON. Stored in
 *     {@code txn.source_row} so a parser bug is fixable by replay rather than re-upload.
 */
public record Transaction(
        TxnId id,
        LocalDate date,
        String eventType,
        String narration,
        List<Posting> postings,
        IdempotencyKey idempotencyKey,
        String sourceRow) {

    public Transaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(narration, "narration");
        Objects.requireNonNull(postings, "postings");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(sourceRow, "sourceRow");
        if (postings.size() < 2) {
            throw new IllegalArgumentException(
                    "a double-entry transaction needs at least two postings, got " + postings.size());
        }
        postings = List.copyOf(postings);
        for (Posting posting : postings) {
            Objects.requireNonNull(posting, "posting");
        }
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(date + " " + eventType + " \"" + narration + "\"");
        for (Posting posting : postings) {
            out.append("\n  ").append(posting).append("  ").append(posting.weight());
        }
        return out.toString();
    }
}
