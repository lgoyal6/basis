#!/usr/bin/env python3
"""
Week 0 feasibility probe for `basis`.

Answers the three gate questions from the build plan and prints a report you can
paste into FEASIBILITY.md.

  Q1  Can I get split histories for free, and does AAPL return the known events?
  Q2  How far back does free dividend history actually go?
  Q3  Is there a programmatic ticker-change source, or do I need a mapping file?

Usage:
    export FMP_KEY="your_key_here"
    python3 feasibility_probe.py

No third-party dependencies. Standard library only.
"""

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import date

BASE = "https://financialmodelingprep.com/stable"
KEY = os.environ.get("FMP_KEY")

# Known-correct ground truth. If the API disagrees with these, the source is wrong,
# not the test.
AAPL_KNOWN_SPLITS = {
    "2020-08-31": (4, 1),
    "2014-06-09": (7, 1),
}

DIVIDEND_DEPTH_TICKERS = ["AAPL", "MSFT", "JNJ", "KO", "PG"]

# The stable base is confirmed; the exact path for splits is not, so try candidates
# and report which one answered. Add any path the current docs name.
SPLIT_PATH_CANDIDATES = [
    "splits",
    "historical-splits",
    "splits-historical",
]

DIVIDEND_PATH_CANDIDATES = [
    "dividends",
    "historical-dividends",
    "dividends-historical",
]

SYMBOL_CHANGE_PATH_CANDIDATES = [
    "symbol-change",
    "symbol-changes",
    "stock-symbol-changes",
]


def get(path, **params):
    """Return (ok, payload_or_error_string)."""
    if not KEY:
        return False, "FMP_KEY is not set in the environment"
    params["apikey"] = KEY
    url = f"{BASE}/{path}?{urllib.parse.urlencode(params)}"
    try:
        with urllib.request.urlopen(url, timeout=20) as resp:
            body = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return False, f"HTTP {e.code}"
    except Exception as e:  # noqa: BLE001
        return False, f"{type(e).__name__}: {e}"
    try:
        data = json.loads(body)
    except json.JSONDecodeError:
        return False, f"non-JSON response: {body[:120]}"
    if isinstance(data, dict) and "Error Message" in data:
        return False, data["Error Message"]
    return True, data


def find_working_path(candidates, **params):
    """Try each candidate path; return (path, data) for the first that returns rows."""
    tried = []
    for path in candidates:
        ok, data = get(path, **params)
        if ok and isinstance(data, list) and data:
            return path, data, tried
        tried.append((path, data if not ok else "empty list"))
    return None, None, tried


def q1_splits():
    print("=" * 72)
    print("Q1  SPLIT HISTORY")
    print("=" * 72)
    path, rows, tried = find_working_path(SPLIT_PATH_CANDIDATES, symbol="AAPL")
    if not path:
        print("  RESULT: FAIL. No candidate split path returned data.")
        for p, err in tried:
            print(f"    /{p:<24} {err}")
        print("\n  This is a kill criterion. Check the current docs at")
        print("  site.financialmodelingprep.com/developer/docs for the splits path,")
        print("  add it to SPLIT_PATH_CANDIDATES, and rerun. If nothing works on the")
        print("  free tier, stop and pivot.")
        return False, None

    print(f"  Working endpoint: /{path}")
    print(f"  Rows returned for AAPL: {len(rows)}")
    print(f"  Sample row: {json.dumps(rows[0])[:200]}")

    by_date = {}
    for r in rows:
        d = r.get("date") or r.get("exDate") or r.get("executionDate")
        if d:
            by_date[str(d)[:10]] = r

    print("\n  Ground-truth check:")
    all_found = True
    for d, (num, den) in AAPL_KNOWN_SPLITS.items():
        row = by_date.get(d)
        if not row:
            print(f"    {d}  MISSING  (expected {num}:{den})")
            all_found = False
            continue
        got_num = row.get("numerator") or row.get("splitFrom") or row.get("newShares")
        got_den = row.get("denominator") or row.get("splitTo") or row.get("oldShares")
        match = "OK" if (got_num, got_den) in ((num, den), (den, num)) else "MISMATCH"
        print(f"    {d}  {match:<9} expected {num}:{den}, got {got_num}:{got_den}")
        if match != "OK":
            all_found = False
            print("      NOTE: check field naming and ratio direction before assuming")
            print("      the data is wrong. Record the actual convention in FEASIBILITY.md,")
            print("      because getting the ratio backwards is a silent basis bug.")

    earliest = min(by_date) if by_date else None
    print(f"\n  Earliest split in AAPL history: {earliest}")
    print(f"  RESULT: {'PASS' if all_found else 'INVESTIGATE'}")
    return all_found, path


def q2_dividends():
    print()
    print("=" * 72)
    print("Q2  DIVIDEND HISTORY DEPTH")
    print("=" * 72)
    path, _, tried = find_working_path(DIVIDEND_PATH_CANDIDATES, symbol="AAPL")
    if not path:
        print("  RESULT: FAIL. No candidate dividend path returned data.")
        for p, err in tried:
            print(f"    /{p:<24} {err}")
        print("\n  Not a kill criterion. Scope v1 to splits plus user-entered")
        print("  dividends and say so in the README.")
        return None

    print(f"  Working endpoint: /{path}\n")
    depths = {}
    today = date.today()
    for t in DIVIDEND_DEPTH_TICKERS:
        ok, rows = get(path, symbol=t)
        if not ok or not isinstance(rows, list) or not rows:
            print(f"    {t:<6} no data ({rows if not ok else 'empty'})")
            continue
        dates = [
            str(r.get("date") or r.get("exDate") or r.get("recordDate"))[:10]
            for r in rows
            if (r.get("date") or r.get("exDate") or r.get("recordDate"))
        ]
        if not dates:
            print(f"    {t:<6} rows present but no parseable date field")
            continue
        earliest = min(dates)
        years = (today - date.fromisoformat(earliest)).days / 365.25
        depths[t] = years
        print(f"    {t:<6} {len(rows):>4} rows, earliest {earliest} ({years:.1f} years)")

    if depths:
        worst = min(depths.values())
        print(f"\n  Shallowest history across sample: {worst:.1f} years")
        if worst < 3:
            print("  RESULT: CONSTRAINED. Free dividend history is shallow.")
            print("  Consequence: reinvested-dividend lots before the cutoff cannot be")
            print("  reconstructed from this source. Either scope v1 to splits only, or")
            print("  accept user-entered dividends, or parse SEC EDGAR. Decide now, and")
            print("  write the decision into the README rather than discovering it in week 3.")
        else:
            print("  RESULT: PASS. Deep enough to reconstruct DRIP lots.")
    return path


def q3_symbol_changes():
    print()
    print("=" * 72)
    print("Q3  TICKER IDENTITY / SYMBOL CHANGES")
    print("=" * 72)
    for p in SYMBOL_CHANGE_PATH_CANDIDATES:
        ok, data = get(p)
        if ok and isinstance(data, list) and data:
            print(f"  Working endpoint: /{p}")
            print(f"  Rows returned: {len(data)}")
            print(f"  Sample row: {json.dumps(data[0])[:200]}")
            hits = [
                r
                for r in data
                if "META" in json.dumps(r).upper() or "FB" == str(r.get("oldSymbol", "")).upper()
            ]
            if hits:
                print(f"  FB -> META style record found: {json.dumps(hits[0])[:200]}")
            else:
                print("  No FB -> META record in this response. It may be a recent-changes")
                print("  feed rather than a full history. Check whether a date range is")
                print("  supported; if not, snapshot it on a schedule and accumulate.")
            print("  RESULT: PASS. SYMBOL_UNRESOLVED breaks can be auto-classified.")
            return p
        print(f"    /{p:<24} {data if not ok else 'empty list'}")
    print("\n  RESULT: FALLBACK. No symbol-change endpoint answered.")
    print("  Ship v1 with a hand-maintained mapping file. This is acceptable, but it")
    print("  must be a known decision, not a week-5 surprise.")
    return None


def main():
    if not KEY:
        print("FMP_KEY is not set.\n")
        print("  1. Sign up at https://site.financialmodelingprep.com/developer/docs")
        print("  2. Choose the free plan")
        print("  3. Copy the API key from the dashboard")
        print("  4. export FMP_KEY='...'  (and put it in .env, which is gitignored)")
        sys.exit(1)

    print(f"\nbasis feasibility probe, run {date.today().isoformat()}")
    print(f"Base URL: {BASE}\n")

    splits_ok, split_path = q1_splits()
    div_path = q2_dividends()
    sym_path = q3_symbol_changes()

    print()
    print("=" * 72)
    print("SUMMARY, paste into FEASIBILITY.md")
    print("=" * 72)
    print(f"  Date run:            {date.today().isoformat()}")
    print(f"  Splits endpoint:     {'/' + split_path if split_path else 'NONE, KILL CRITERION'}")
    print(f"  Splits ground truth: {'PASS' if splits_ok else 'FAIL or INVESTIGATE'}")
    print(f"  Dividends endpoint:  {'/' + div_path if div_path else 'NONE'}")
    print(f"  Symbol changes:      {'/' + sym_path if sym_path else 'NONE, use mapping file'}")
    print()
    print("  Also record from the FMP dashboard, since it governs how many tickers")
    print("  can be refreshed per day and therefore the caching design:")
    print("    Stated free-tier request limit: ____ per ____")
    print()

    if not splits_ok:
        print("  GATE: splits did not validate. Do not start week 1 until this passes")
        print("  or you have consciously chosen the pivot.")
        sys.exit(2)


if __name__ == "__main__":
    main()
