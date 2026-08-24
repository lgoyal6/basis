# basis

Independently recomputes brokerage positions from transaction history and reports every disagreement with the broker.

Given the same trades a broker saw, `basis` replays them through a multi-commodity double-entry ledger, applies corporate actions, computes lot-level cost basis, and then compares its answer against the position the broker itself reports. Every disagreement becomes a **break** with a probable cause attached.

Instead of "you have 30 fewer shares than expected", you get "that is a 4-to-1 ratio, there is an unapplied split on this date, apply it?"

## What it refuses to do

- It is not a tax product. It does not generate tax forms.
- It does not guess. Ambiguous corporate actions raise a break and ask.
- It never touches broker credentials. Statement upload only.

## Status

Week 4. Ledger core, distributions, transfers, corporate actions, reconciliation.

- Multi-commodity double-entry postings, lot-level cost basis, and
  FIFO/LIFO/HIFO/specific-lot selection
- Buys, sells, fees, opening balances, cash dividends with withholding, and
  transfers that carry their lots so the holding period does not restart
- Splits, reverse splits, stock dividends and spin offs, which restate a position
  without changing what it is worth
- All eight invariants, asserted over generated histories after every step

- Reconciliation against a broker position snapshot, producing breaks with a
  probable cause attached

Every event the ledger declares is now handled. Still to come: statement parsers,
and cash in lieu of fractional shares. No web layer.

### What a break looks like

Given a history that never applied Apple's 2020 split, and a statement saying 40
shares where basis computed 10:

```
2026-03-31 Assets:Broker:IBKR:AAPL AAPL QUANTITY_MISMATCH: broker 40, computed 10.
The broker reports 4 for 1 of what basis computed, and AAPL had a 4 for 1 split
effective 2026-02-20 that this history never applied.
Apply the 4 for 1 split of AAPL dated 2026-02-20 and reconcile again.
```

Without reference data the same break still finds the ratio, says so is arithmetic
rather than evidence, and is marked not confident. basis does not guess.

See `docs/FEASIBILITY.md` for the week 0 feasibility gate and
`docs/ARCHITECTURE.md` for every design decision and why it was taken.

### Building

Needs JDK 21 and Docker (Testcontainers spins up Postgres 16 for the persistence
tests).

```
JAVA_HOME=/path/to/jdk-21 ./gradlew test
```

The suite is fully green. Invariant 8, corporate action value preservation, was a
deliberately failing placeholder through week 2 and is now a real property.

## License

MIT
