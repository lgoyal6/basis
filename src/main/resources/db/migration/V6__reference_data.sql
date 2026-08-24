-- Cache of corporate action reference data fetched from a market data provider,
-- keyed by the natural key of the thing itself rather than by a request.
--
-- fetched_at is what makes the cache honest: week 0 established that the free FMP
-- tier caps how many symbols can be refreshed per day, so the ledger has to be able
-- to say how stale its reference data is rather than pretend it is current. See
-- docs/FEASIBILITY.md.
--
-- No symbol change data is cached here. FMP's /symbol-change endpoint is paywalled
-- (HTTP 402 in the week 0 probe), so ticker renames come from a hand maintained
-- mapping file instead. That is a known decision, not an oversight.

CREATE TABLE reference_data (
    symbol     TEXT        NOT NULL,
    event_type TEXT        NOT NULL,
    event_date DATE        NOT NULL,
    payload    JSONB       NOT NULL,
    source     TEXT        NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (symbol, event_type, event_date)
);

COMMENT ON COLUMN reference_data.fetched_at IS
    'When this row was last confirmed against the provider. Staleness is reportable, not assumed away.';

CREATE INDEX reference_data_staleness_idx ON reference_data (fetched_at);
CREATE INDEX reference_data_symbol_idx ON reference_data (symbol, event_type);
