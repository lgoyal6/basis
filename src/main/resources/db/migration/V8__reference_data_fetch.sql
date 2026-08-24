-- Records that a fetch happened, separately from what it returned.
--
-- reference_data holds one row per split. That is enough to answer "what splits does
-- AAPL have" and not enough to answer "have we ever looked". A symbol with no splits
-- and a symbol nobody has fetched both have zero rows, and reconciliation needs to
-- tell them apart: the first says a corporate action is not the explanation, the
-- second says nobody has checked yet. Those are different sentences to put in front
-- of a person, and only one of them is a reason to go fetch something.
--
-- The distinction is not academic on the free tier. A symbol outside the subscription
-- returns HTTP 402, not an empty list, so "cannot check" is the common case rather
-- than the rare one.
--
-- last_attempt_at and last_success_at are separate so that a failed refresh does not
-- erase the memory of the last good one. Rate limiting reads the success; error
-- reporting reads the attempt.

CREATE TABLE reference_data_fetch (
    symbol          TEXT        NOT NULL,
    event_type      TEXT        NOT NULL,
    last_attempt_at TIMESTAMPTZ NOT NULL,
    last_success_at TIMESTAMPTZ,
    last_outcome    TEXT        NOT NULL,
    last_status     INTEGER,
    last_detail     TEXT,
    rows_returned   INTEGER,
    attempts        BIGINT      NOT NULL DEFAULT 1,

    PRIMARY KEY (symbol, event_type),

    CONSTRAINT reference_data_fetch_outcome CHECK (
        last_outcome IN ('OK', 'NOT_AVAILABLE', 'UNAUTHORIZED', 'TRANSPORT_ERROR', 'UNEXPECTED')
    ),
    -- A successful fetch has to say how many rows it saw, and a failed one cannot.
    CONSTRAINT reference_data_fetch_rows_with_success CHECK (
        (last_outcome = 'OK') = (rows_returned IS NOT NULL)
    ),
    CONSTRAINT reference_data_fetch_success_timestamp CHECK (
        last_outcome <> 'OK' OR last_success_at IS NOT NULL
    )
);

COMMENT ON TABLE reference_data_fetch IS
    'One row per symbol and event type, recording that a fetch was attempted. Distinguishes "checked, nothing there" from "never checked" and from "could not check".';

CREATE INDEX reference_data_fetch_staleness_idx ON reference_data_fetch (event_type, last_success_at NULLS FIRST);
