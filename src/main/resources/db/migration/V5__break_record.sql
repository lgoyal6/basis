-- A break is a disagreement between what basis computed and what the broker
-- reported, with a probable cause attached.
--
-- Not derived state. A break carries a human's judgement once it has been triaged,
-- and that judgement is not recomputable from posting, so this table survives a
-- truncate and replay. It is the one table in the reconciliation path that does.

CREATE TABLE break_record (
    id                    BIGSERIAL     PRIMARY KEY,
    detected_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    as_of_date            DATE          NOT NULL,
    account               TEXT          NOT NULL,
    commodity             TEXT          NOT NULL,
    break_type            TEXT          NOT NULL,
    broker_quantity       NUMERIC(28,8),
    computed_quantity     NUMERIC(28,8),
    broker_amount_minor   BIGINT,
    computed_amount_minor BIGINT,
    currency              CHAR(3),
    probable_cause        TEXT,
    status                TEXT          NOT NULL DEFAULT 'OPEN',
    resolved_at           TIMESTAMPTZ,
    resolution_note       TEXT,

    CONSTRAINT break_record_status CHECK (status IN ('OPEN', 'ACCEPTED', 'REJECTED', 'RESOLVED')),
    CONSTRAINT break_record_resolved_has_timestamp CHECK ((status = 'OPEN') = (resolved_at IS NULL)),

    -- A break has to disagree about something, or it is not a break.
    CONSTRAINT break_record_has_a_disagreement CHECK (
        broker_quantity IS NOT NULL OR broker_amount_minor IS NOT NULL
    ),
    CONSTRAINT break_record_amount_has_currency CHECK (
        (broker_amount_minor IS NULL AND computed_amount_minor IS NULL) OR currency IS NOT NULL
    )
);

COMMENT ON COLUMN break_record.probable_cause IS
    'Why basis thinks the two disagree, for example an unapplied 4 to 1 split. The point of the product: not "you have 30 fewer shares" but "that is a 4 to 1 ratio".';

CREATE INDEX break_record_open_idx ON break_record (as_of_date, account) WHERE status = 'OPEN';
