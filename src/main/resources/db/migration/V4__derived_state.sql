-- Derived state. Every table here is truncatable at any moment and rebuildable
-- from posting alone.
--
-- Deliberately no foreign keys, not even realized_gain.txn_id to txn. Referential
-- integrity here is guaranteed by the projection that writes these rows, not by the
-- schema, and adding constraints would make a truncate and replay need CASCADE and
-- a particular ordering. A derived table that is awkward to drop stops being
-- treated as derived. See docs/ARCHITECTURE.md section 10.

CREATE TABLE position (
    account   TEXT          NOT NULL,
    commodity TEXT          NOT NULL,
    quantity  NUMERIC(28,8) NOT NULL,

    PRIMARY KEY (account, commodity)
);

COMMENT ON TABLE position IS 'Derived from posting. Truncate and replay at will.';

CREATE TABLE lot (
    lot_id             TEXT          PRIMARY KEY,
    account            TEXT          NOT NULL,
    commodity          TEXT          NOT NULL,
    acquisition_date   DATE          NOT NULL,
    unit_cost          NUMERIC(28,6) NOT NULL,
    unit_cost_currency CHAR(3)       NOT NULL,
    original_quantity  NUMERIC(28,8) NOT NULL,
    remaining_quantity NUMERIC(28,8) NOT NULL,

    -- Invariant 2, lot conservation, enforced by the database. remaining is between
    -- zero and what was acquired, so no projection bug can write a lot that has
    -- disposed more than it ever held.
    CONSTRAINT lot_original_quantity_positive CHECK (original_quantity > 0),
    CONSTRAINT lot_remaining_not_negative     CHECK (remaining_quantity >= 0),
    CONSTRAINT lot_remaining_within_original  CHECK (remaining_quantity <= original_quantity)
);

COMMENT ON TABLE lot IS 'Derived from posting. Truncate and replay at will.';

CREATE INDEX lot_holding_open_idx ON lot (account, commodity, acquisition_date, lot_id)
    WHERE remaining_quantity > 0;

CREATE TABLE realized_gain (
    id             BIGSERIAL     PRIMARY KEY,
    txn_id         UUID          NOT NULL,
    sale_date      DATE          NOT NULL,
    account        TEXT          NOT NULL,
    commodity      TEXT          NOT NULL,
    quantity       NUMERIC(28,8) NOT NULL,
    proceeds_minor BIGINT        NOT NULL,
    basis_minor    BIGINT        NOT NULL,
    gain_minor     BIGINT        NOT NULL,
    currency       CHAR(3)       NOT NULL,

    -- Invariant 5, the proceeds identity, enforced by the database in whole minor
    -- units. The three numbers are computed from three different subsets of the
    -- postings, so this constraint is a real check and not a restatement.
    CONSTRAINT realized_gain_proceeds_identity CHECK (gain_minor = proceeds_minor - basis_minor)
);

COMMENT ON TABLE realized_gain IS 'Derived from posting. Truncate and replay at will.';

CREATE INDEX realized_gain_txn_idx ON realized_gain (txn_id);
CREATE INDEX realized_gain_holding_idx ON realized_gain (account, commodity, sale_date);
