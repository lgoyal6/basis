-- A transaction: a set of postings that happened together on one date.
--
-- source_row holds the original imported row verbatim, forever. That is what makes
-- a parser bug fixable by replay rather than by asking a user to re upload a
-- statement they may no longer have. JSONB rather than TEXT so that a row can be
-- queried when diagnosing a break, not because the ledger reads it.
--
-- idempotency_key is BYTEA UNIQUE, so re importing the same statement is a no op
-- enforced by the database rather than by application code that could be bypassed.

CREATE TABLE txn (
    id              UUID        PRIMARY KEY,
    import_batch_id BIGINT      NOT NULL REFERENCES import_batch (id) ON DELETE CASCADE,
    txn_date        DATE        NOT NULL,
    event_type      TEXT        NOT NULL,
    narration       TEXT        NOT NULL,
    idempotency_key BYTEA       NOT NULL UNIQUE,
    source_row      JSONB       NOT NULL,
    recorded_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN txn.source_row IS
    'The original imported row, verbatim and permanent. A parser bug is fixed by replaying this, never by re uploading.';

-- ON DELETE CASCADE is what makes rolling back a crashed batch a single DELETE on
-- import_batch, with no chance of leaving orphan transactions behind.
CREATE INDEX txn_import_batch_idx ON txn (import_batch_id);
CREATE INDEX txn_date_idx ON txn (txn_date);
