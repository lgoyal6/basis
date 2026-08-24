# basis architecture

Living record of design decisions. Every entry states the options that were on the
table, the one that was picked, and why. Nothing here is guessed silently.

Scope of this document as of week 1: the ledger core only. No parsers, no web
layer, no corporate actions.

## 0. Persistence: Spring JdbcClient, not JPA

Mandated, recorded here for the rationale. A ledger is an append-only log where
write ordering is part of the semantics: a transaction's postings must land in a
known order because replay reads them back in `posting.id` order and the derived
state hash must be byte-identical (invariant 7). JPA's dirty checking and flush
ordering is an abstraction that actively fights that requirement, and lazy
loading hides the query count in a component whose whole job is to be auditable.
JdbcClient gives explicit SQL, explicit ordering, and no session state.

## 1. Posting shape: one uniform record, not a cash/security split

Options:

- **(a) Uniform posting.** Every posting is `(account, commodity, quantity, cost?)`.
  Cash is units of a currency commodity, so `-1501.00 USD` is a quantity of the
  `USD` commodity, exactly as Beancount models it.
- **(b) Sealed hierarchy.** `CashPosting(account, Money)` and
  `SecurityPosting(account, Commodity, Quantity, Cost)`, weight derived per case.

Picked **(a)**. The balance invariant is one rule over one list; (b) makes it two
cases that can drift, which is the same failure mode the mandate rejects for
realized gain. The mandate's own wording ("each posting has account, commodity,
amount, and for security postings a cost") describes (a) with an optional cost.

Consequence: a currency posting whose quantity is not exact at that currency's
minor unit (a fractional cent) is rejected at construction rather than rounded.

## 2. Weight at cost is rounded per posting, not per transaction

A posting's weight is its contribution to the balance check, in `Money`:

- cost present: `round(quantity * cost.unitCost)`, HALF_EVEN, to the currency's
  minor unit.
- cost absent, commodity is a currency: the quantity itself, required to be exact
  in minor units.
- cost absent, commodity is not a currency: rejected in week 1.

Options were rounding once at the transaction level (keeping full precision per
posting) or rounding each posting independently.

Picked **per posting**. It is what makes invariant 5 exact rather than
approximate: total basis is *defined* as the sum of the disposal postings'
weights, so `proceeds - basis = gain` holds in whole minor units for a multi-lot
sale of fractional shares. Rounding later would leave sub-cent residue that has
to be assigned to some lot arbitrarily, and any two code paths that assign it
differently disagree.

## 3. Fees are expensed, not capitalised into basis

Options:

- **(a) Expense them.** `Expenses:Commissions` takes the fee; the lot's unit cost
  is the clean trade price; realized gain is gross proceeds minus basis.
- **(b) Capitalise them.** A buy commission raises the lot's cost basis, a sell
  commission reduces proceeds. This is the US tax treatment.

Picked **(a)**. The mandate's worked example fixes it: buying 10 AAPL at 150.00
with 1.00 commission books the lot at `{150.00 USD}`, not `{150.10 USD}`, and the
commission appears as its own `Expenses:Commissions` posting. Choosing (b) would
contradict the specification's own arithmetic.

This is a known divergence from tax-correct basis. `basis` is explicitly not a tax
product (see README), and fee capitalisation is a lot-level basis adjustment that
belongs with the other basis adjustments in the corporate actions work. Recorded
here so it is a decision and not a bug.

## 4. Realized gain is the balancing plug, never a computed field

A transaction is built by naming its known postings and then naming exactly one
*plug* account and currency. The plug posting's amount is the residual required to
bring that currency's weights to zero. For a sale the plug is
`Income:CapitalGains:Realized`; for a purchase or a fee it is the cash account;
for an opening balance it is `Equity:Opening-Balances`.

There is therefore no code path that computes a gain. The gain is read back out of
the ledger as the negation of the plug posting. One invariant, one arithmetic.

A plug whose residual is zero emits no posting. A wash sale simply has no gain
posting, and readers treat the empty sum as zero.

## 5. Multi-currency balance: zero per currency, no FX in week 1

The invariant is checked by grouping posting weights by currency and requiring
each group to sum to zero, rather than requiring a single currency per
transaction. This is a strict superset of the single-currency rule, so it needs no
revisiting when FX arrives, and it correctly refuses a transaction that tries to
balance USD against EUR without a stated rate.

## 6. `Lot` is an immutable snapshot that carries `remainingQuantity`

Options:

- **(a) Lot as pure identity.** `(id, acquisitionDate, unitCost)` only, with
  remaining quantity living exclusively in the derived-state layer.
- **(b) Lot as an immutable snapshot** of a derived `lot` row, carrying original
  and remaining quantity, with `consume(qty)` returning a new `Lot`.

Picked **(b)**. Lot selection strategies need remaining quantity to choose at all,
and invariant 2 (`remaining = acquired - disposed`, never negative) is a statement
about exactly these two fields, so putting them on the type lets the type enforce
half of the invariant in its constructor. Immutability is preserved: nothing
mutates a `Lot`, and the authoritative copy is still the derived table, which can
be truncated and rebuilt from `posting` at any time.

`Cost(lotId, unitCost, acquisitionDate)` is the separate, smaller thing that
appears in `{...}` on a posting. It is the pin, not the lot.

## 7. Average cost is restricted, and unimplemented

The US permits average cost only for mutual fund shares and certain dividend
reinvestment plans, never for ordinary equities. Encoding that as a comment would
not be encoding it, so `AverageCostLotSelection` behaves in two distinct ways:

- commodity is not average-cost eligible (equity, ETF, option): throws
  `AverageCostNotPermittedException`. This is the restriction, and it is tested.
- commodity is eligible (mutual fund, or a lot flagged as DRIP): throws
  `UnsupportedOperationException`. Week 1 does not implement the method itself.

Two different exceptions because they are two different facts. The first is a
permanent rule about the domain; the second is a statement about the calendar.

## 8. Lot selection is totally ordered, deliberately

FIFO, LIFO and HIFO all break ties on `lotId` after their primary key
(acquisition date, or unit cost then date for HIFO). Without a total order, two
lots acquired the same day at the same price could be consumed in either order,
and invariant 7 would fail intermittently: the derived state hash is only
byte-stable if selection is deterministic. A flaky invariant is worse than no
invariant.

Selecting more than the open quantity raises `InsufficientLotsException`. In later
weeks a short position or a missing acquisition becomes a `break_record`; in week 1
it is an error, because there is nothing yet that could have caused it legitimately.

## 9. An uncommitted import batch on startup is rolled back, not resumed

`import_batch.committed_at` is nullable and is the crash marker. The mandate
requires that a null on startup is resolved one way or the other and never left
ambiguous. Options were rollback or resume.

Picked **rollback**: delete the batch's transactions (postings cascade), mark the
batch abandoned, and log it. Resuming requires knowing which source rows were
already consumed, which is parser state, and there is no parser until week 2.
Rollback is safe because `txn.source_row` keeps every original row verbatim, so
the correct recovery is always to replay the file. When the parser lands, resume
becomes available and this decision should be revisited.

## 10. Derived state is disposable by construction

`position`, `lot` and `realized_gain` hold nothing that is not recomputable from
`posting`. They carry no external identifiers and nothing writes to them except
the projector. Invariant 7 is the test that this stays true: truncate all three,
replay `posting` in `id` order, and the SHA-256 of the canonical dump must be
byte-identical.

## 11. Invariant 8 is intentionally red

`CorporateActionValuePreservationTest` fails on purpose. Corporate action value
preservation is week 3 work, and the mandate asks for the placeholder to fail
rather than be skipped, so that the gap is visible in every test run instead of
hiding behind a green tick. It is tagged `week3`.

Expect exactly one failing test at the end of week 1. Any other red is a real
regression.

## 12. Balance is checked, not made unrepresentable

Options:

- **(a) Enforce in the constructor.** `Transaction` refuses to exist unless its
  postings sum to zero. An unbalanced transaction becomes unrepresentable.
- **(b) Enforce at the boundary.** The constructor validates structure; a separate
  `BalanceChecker` is called before anything is written, and the database never
  sees an unbalanced transaction.

Picked **(b)**, with a caveat worth stating plainly, because (a) is the more
defensive design and the reason for not taking it is not laziness.

If the constructor enforced balance, invariant 1 would become untestable: no test
could construct a counterexample, so the property test would assert something that
the type system already guaranteed and would therefore detect no regression in the
weight arithmetic. The interesting failure is not "someone built an unbalanced
transaction", it is "`Posting.weight()` rounds wrongly, and the sum we believed
was zero is not". A checker that can be pointed at a hand-built adversarial
transaction catches that; a constructor that rejects it cannot be tested against it.

The safety that (a) would have given is recovered at the only place it matters:
`LedgerService` calls the checker on every transaction before persisting, and the
persistence layer will not write a transaction it has not seen pass. The window in
which an unbalanced `Transaction` object can exist is a few statements inside a
builder.
