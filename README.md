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

- A market data client that populates the split cache, so most split-shaped breaks
  explain themselves

- A Fidelity statement importer, so transactions get in without being hand fed

Every event the ledger declares is now handled. Still to come: importers for other
brokers, and cash in lieu of fractional shares. No web layer.

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

There are three answers, not two. If the split history was fetched and contains no
such split, the break says so and points at missing trades instead: a ratio that
provably is not a corporate action is a different finding, and it gets its own code.

### Market data

Split history comes from Financial Modeling Prep and is cached in `reference_data`.
It is off by default and touches the network only when switched on:

```
set -a; source .env; set +a          # Spring does not read .env itself
BASIS_FMP_ENABLED=true ./gradlew ... # or set basis.fmp.enabled
```

Two tests call the provider for real and are excluded from every default run. Run
them with `./gradlew test -PwithNetwork` and a key in the environment.

See `docs/FEASIBILITY.md` for the week 0 feasibility gate and
`docs/ARCHITECTURE.md` for every design decision and why it was taken.

### Running it

Needs JDK 21 and a Postgres 16. Build a jar and ask it what it does:

```
./gradlew bootJar
java -jar build/libs/basis.jar --help
```

Import a statement, then reconcile a position snapshot against it:

```
export BASIS_DB_URL=jdbc:postgresql://localhost:5432/basis
export BASIS_DB_USER=basis BASIS_DB_PASSWORD=basis

java -jar build/libs/basis.jar import fidelity Assets:Broker:Fidelity history.csv
java -jar build/libs/basis.jar status
java -jar build/libs/basis.jar reconcile Assets:Broker:Fidelity positions.csv --as-of 2026-03-31
java -jar build/libs/basis.jar breaks Assets:Broker:Fidelity
java -jar build/libs/basis.jar settle 1 --accept --note "applied the split"
```

Exit codes: `0` ok, `1` failed, `2` bad usage, `3` reconcile found breaks. The last
one is deliberately not a failure, so a pipeline can act on a disagreement.

Statement import currently understands **Fidelity**'s Accounts History CSV export.
It was built from knowledge of that format rather than a real file, so expect the
column names or action wording to need a correction on first contact. Both live in
lookup tables (`FidelityCsvParser.COLUMN_ALIASES` and `FidelityActions`), so a fix
is a one line edit. A row it cannot read stops the import and names the line rather
than being silently skipped.

The position snapshot for `reconcile` is basis's own format, not a broker's:

```
symbol,quantity,cost_basis,kind
AAPL,40,,EQUITY
MSFT,10,3000.00,EQUITY
USD,1520.55,,CURRENCY      # only with --with-cash
```

`cost_basis` is optional, because most statements report a quantity and a market
value and nothing else. Whether the file covers cash is stated with `--with-cash`
rather than guessed, because an omitted cash line means "the statement did not say"
on most reports and "the balance is zero" on some.

Ticker renames live in `config/symbol-changes.csv`, maintained by hand because the
provider's symbol change endpoint is paywalled.

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
