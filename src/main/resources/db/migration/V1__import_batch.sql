-- An import batch is the unit of crash recovery.
--
-- committed_at is the crash marker. A row with committed_at IS NULL and
-- abandoned_at IS NULL was in flight when the process died, and startup must
-- resolve it one way or the other. It is never left ambiguous: see
-- StartupRecovery and docs/ARCHITECTURE.md section 9.
--
-- abandoned_at exists so that a rolled back batch leaves a trail instead of
-- vanishing. Deleting the row would make the recovery itself unauditable, which
-- for a tool whose entire output is "here is why your broker and I disagree"
-- would be the wrong trade.

CREATE TABLE import_batch (
    id             BIGSERIAL    PRIMARY KEY,
    source         TEXT        NOT NULL,
    filename       TEXT        NOT NULL,
    content_hash   BYTEA       NOT NULL,
    row_count      INTEGER,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    committed_at   TIMESTAMPTZ,
    abandoned_at   TIMESTAMPTZ,
    abandon_reason TEXT,

    CONSTRAINT import_batch_not_both_committed_and_abandoned
        CHECK (committed_at IS NULL OR abandoned_at IS NULL),
    CONSTRAINT import_batch_abandon_reason_with_abandonment
        CHECK ((abandoned_at IS NULL) = (abandon_reason IS NULL))
);

COMMENT ON COLUMN import_batch.committed_at IS
    'NULL while the batch is in flight. A NULL here on startup, with abandoned_at also NULL, means the process crashed mid import.';

CREATE INDEX import_batch_in_flight_idx ON import_batch (started_at)
    WHERE committed_at IS NULL AND abandoned_at IS NULL;
