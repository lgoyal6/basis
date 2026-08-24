
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



========================================================================
ADDENDUM, 2026-08-24: what the fetcher had to be built against
========================================================================

Probed again while building the reference data client. The week 0 answers
above still hold. Four further things were established, three of which
would have been got wrong from memory.

  /splits, AAPL             still 200, still a JSON array, still 5 rows,
                            still anchored on the 2020-08-31 4:1

  Rate limit headers        NONE. The provider returns no X-RateLimit
                            headers of any kind, on success or failure.
                            The limit is therefore not discoverable from
                            a response and cannot be backed off from.

  Unknown or unsubscribed   HTTP 402, NOT an empty array. This is the
  symbol                    important one. On the free tier, "we cannot
                            check this symbol" and "this symbol has no
                            splits" are different answers, and the second
                            is much rarer than expected.

  402 body                  PLAIN TEXT, not JSON:
                            "Premium Query Parameter: 'Special Endpoint :
                            This value set for 'symbol' is not available
                            under your current subscription..."
                            A JSON parser pointed at it throws.

  401 body (bad key)        JSON: {"Error Message": "Invalid API KEY..."}
                            So the provider signals failure two different
                            ways, in two different formats, and a client
                            has to handle both.

CONSEQUENCES FOR THE DESIGN

  Because a 402 and an empty result are indistinguishable if you only
  store the rows that came back, the fetcher records the fetch itself in
  reference_data_fetch. That is what lets reconciliation say "checked,
  no split exists" rather than "no split found", which are different
  claims and only one of them rules a corporate action out.

  Because there are no rate limit headers, basis.fmp.daily-request-budget
  is a ceiling the application imposes rather than one it derives, and
  refresh spends it on symbols with an open break first.

STILL OPEN

  Stated free-tier request limit: ____ per ____

  Not answerable from the API: no headers, and no error was provoked by
  the handful of requests this addendum cost. Read it off the FMP
  dashboard and set basis.fmp.daily-request-budget to match. The default
  of 50 is a guess chosen to be small.
