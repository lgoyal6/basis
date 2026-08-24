package com.basis.domain.event;

import com.basis.domain.Account;
import com.basis.domain.IdempotencyKey;
import java.time.LocalDate;

/**
 * Something that happened to an account, as reported by a broker statement.
 *
 * <p>Sealed so that the handler's switch is exhaustive: adding a corporate action to
 * this list breaks compilation everywhere that has to deal with it, which is the point.
 * Week 1 implements {@link Buy}, {@link Sell}, {@link Fee} and {@link OpeningBalance};
 * the rest are declared here as data and the handler refuses them with
 * {@code UnsupportedOperationException} until the week that owns them.
 *
 * <p>An event is a statement about the world, not an instruction. It carries no lot
 * identifiers it did not come with, and it never names a posting. Turning it into
 * postings is the handler's job, and it is the only place that arithmetic happens.
 */
public sealed interface LedgerEvent
        permits Buy, Sell, CashDividend, StockDividend, Split, ReverseSplit, SpinOff, Fee, Transfer, OpeningBalance {

    /** Settlement or effective date, whichever the statement reported. */
    LocalDate date();

    /** The broker account this happened in, for example {@code Assets:Broker:IBKR}. */
    Account account();

    /**
     * The broker's own identifier for this row: an execution id where one exists, or
     * the row's ordinal within its file where one does not. Two fills that are
     * otherwise byte identical are told apart by this and nothing else.
     */
    String externalRef();

    /**
     * The original imported row, verbatim, as JSON. Kept forever in
     * {@code txn.source_row} so that a parser bug is fixable by replay.
     */
    String sourceRow();

    default String type() {
        return getClass().getSimpleName();
    }

    /**
     * The key that makes re-importing the same statement a no-op.
     *
     * <p>Derived from the verbatim source row, so the same broker row always produces
     * the same key whichever batch carried it, plus the broker's reference so that two
     * genuinely distinct fills with identical contents do not collide. Uniqueness is
     * enforced by {@code txn.idempotency_key BYTEA UNIQUE}, not by application code.
     */
    default IdempotencyKey idempotencyKey() {
        return IdempotencyKey.of(type(), date().toString(), account().name(), externalRef(), sourceRow());
    }
}
