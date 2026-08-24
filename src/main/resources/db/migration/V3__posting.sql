-- Postings are the ledger. Everything else in this schema is either the paperwork
-- around them or something recomputable from them.
--
-- id is the replay order. Invariant 7 reads this table in id order, so the sequence
-- is load bearing and not just a surrogate key.
--
-- weight_minor is the posting's contribution to the balance check, in minor units,
-- as computed at write time. It is derivable from the other columns, and it is
-- stored anyway for two reasons: the sum to zero invariant becomes checkable in SQL
-- by anyone auditing the database without running the application, and a future
-- change to the rounding rule cannot silently restate the weight of a posting that
-- was already written. See docs/ARCHITECTURE.md section 13.

CREATE TABLE posting (
    id               BIGSERIAL     PRIMARY KEY,
    txn_id           UUID          NOT NULL REFERENCES txn (id) ON DELETE CASCADE,
    ordinal          SMALLINT      NOT NULL,
    account          TEXT          NOT NULL,
    commodity        TEXT          NOT NULL,
    commodity_class  TEXT          NOT NULL,
    quantity         NUMERIC(28,8) NOT NULL,
    cost_unit_amount NUMERIC(28,6),
    cost_currency    CHAR(3),
    cost_date        DATE,
    lot_id           TEXT,
    weight_minor     BIGINT        NOT NULL,
    weight_currency  CHAR(3)       NOT NULL,

    CONSTRAINT posting_ordinal_unique_per_txn UNIQUE (txn_id, ordinal),

    -- A cost is all four columns or none of them. A half written cost annotation is
    -- a posting pinned to a lot nobody can identify.
    CONSTRAINT posting_cost_all_or_nothing CHECK (
        (cost_unit_amount IS NULL AND cost_currency IS NULL AND cost_date IS NULL AND lot_id IS NULL)
        OR
        (cost_unit_amount IS NOT NULL AND cost_currency IS NOT NULL AND cost_date IS NOT NULL AND lot_id IS NOT NULL)
    ),

    -- Cash carries no cost, securities must carry one. The same rule the Posting
    -- record enforces, restated here so that a direct SQL write cannot break it.
    CONSTRAINT posting_cash_carries_no_cost CHECK (
        commodity_class <> 'CURRENCY' OR lot_id IS NULL
    ),
    CONSTRAINT posting_security_carries_cost CHECK (
        commodity_class = 'CURRENCY' OR lot_id IS NOT NULL
    ),

    CONSTRAINT posting_cost_currency_matches_weight CHECK (
        cost_currency IS NULL OR cost_currency = weight_currency
    )
);

COMMENT ON COLUMN posting.id IS
    'Replay order. Derived state is rebuilt by reading this table in id order, so this sequence is load bearing.';

CREATE INDEX posting_txn_idx ON posting (txn_id);
CREATE INDEX posting_holding_idx ON posting (account, commodity);
CREATE INDEX posting_lot_idx ON posting (lot_id) WHERE lot_id IS NOT NULL;
