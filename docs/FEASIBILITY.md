
basis feasibility probe, run 2026-08-24
Base URL: https://financialmodelingprep.com/stable

========================================================================
Q1  SPLIT HISTORY
========================================================================
  Working endpoint: /splits
  Rows returned for AAPL: 5
  Sample row: {"symbol": "AAPL", "date": "2020-08-31", "numerator": 4, "denominator": 1, "splitType": "stock-split"}

  Ground-truth check:
    2020-08-31  OK        expected 4:1, got 4:1
    2014-06-09  OK        expected 7:1, got 7:1

  Earliest split in AAPL history: 1987-06-16
  RESULT: PASS

========================================================================
Q2  DIVIDEND HISTORY DEPTH
========================================================================
  Working endpoint: /dividends

    AAPL     92 rows, earliest 1987-05-11 (39.3 years)
    MSFT     90 rows, earliest 2003-02-19 (23.5 years)
    JNJ     227 rows, earliest 1970-02-16 (56.5 years)
    KO      226 rows, earliest 1970-06-08 (56.2 years)
    PG     no data (HTTP 402)

  Shallowest history across sample: 23.5 years
  RESULT: PASS. Deep enough to reconstruct DRIP lots.

========================================================================
Q3  TICKER IDENTITY / SYMBOL CHANGES
========================================================================
    /symbol-change            HTTP 402
    /symbol-changes           HTTP 404
    /stock-symbol-changes     HTTP 404

  RESULT: FALLBACK. No symbol-change endpoint answered.
  Ship v1 with a hand-maintained mapping file. This is acceptable, but it
  must be a known decision, not a week-5 surprise.

========================================================================
SUMMARY, paste into FEASIBILITY.md
========================================================================
  Date run:            2026-08-24
  Splits endpoint:     /splits
  Splits ground truth: PASS
  Dividends endpoint:  /dividends
  Symbol changes:      NONE, use mapping file

  Also record from the FMP dashboard, since it governs how many tickers
  can be refreshed per day and therefore the caching design:
    Stated free-tier request limit: ____ per ____

