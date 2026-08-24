# basis

Independently recomputes brokerage positions from transaction history and reports every disagreement with the broker.

Given the same trades a broker saw, `basis` replays them through a multi-commodity double-entry ledger, applies corporate actions, computes lot-level cost basis, and then compares its answer against the position the broker itself reports. Every disagreement becomes a **break** with a probable cause attached.

Instead of "you have 30 fewer shares than expected", you get "that is a 4-to-1 ratio, there is an unapplied split on this date, apply it?"

## What it refuses to do

- It is not a tax product. It does not generate tax forms.
- It does not guess. Ambiguous corporate actions raise a break and ask.
- It never touches broker credentials. Statement upload only.

## Status

Week 2. Ledger core plus cash distributions and movement between accounts.

- Multi-commodity double-entry postings, lot-level cost basis, and
  FIFO/LIFO/HIFO/specific-lot selection
- Buys, sells, fees, opening balances, cash dividends with withholding, and
  transfers that carry their lots so the holding period does not restart
- The seven invariants that hold it together, asserted over generated histories
  after every step

No parsers, no web layer, no corporate actions yet.

See `docs/FEASIBILITY.md` for the week 0 feasibility gate and
`docs/ARCHITECTURE.md` for every design decision and why it was taken.

### Building

Needs JDK 21 and Docker (Testcontainers spins up Postgres 16 for the persistence
tests).

```
JAVA_HOME=/path/to/jdk-21 ./gradlew test
```

One test fails on purpose: `CorporateActionValuePreservationTest` is invariant 8,
corporate action value preservation, which is week 3 work. It fails rather than
being skipped so the gap shows up in every run instead of hiding behind a green
tick. Exclude it with `-PexcludeTags=week3`. Anything else red is a regression.

## License

MIT
