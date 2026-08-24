# basis

Independently recomputes brokerage positions from transaction history and reports every disagreement with the broker.

Given the same trades a broker saw, `basis` replays them through a multi-commodity double-entry ledger, applies corporate actions, computes lot-level cost basis, and then compares its answer against the position the broker itself reports. Every disagreement becomes a **break** with a probable cause attached.

Instead of "you have 30 fewer shares than expected", you get "that is a 4-to-1 ratio, there is an unapplied split on this date, apply it?"

## What it refuses to do

- It is not a tax product. It does not generate tax forms.
- It does not guess. Ambiguous corporate actions raise a break and ask.
- It never touches broker credentials. Statement upload only.

## Status

Week 0. Feasibility gate. See `docs/FEASIBILITY.md`.

## License

MIT
