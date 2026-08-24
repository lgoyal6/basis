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

## 13. `posting.weight_minor` is stored, though it is derivable

A posting's weight at cost is a pure function of its own other columns, so storing
it duplicates information. It is stored anyway.

Options:

- **(a) Compute on read.** The only definition of a weight is
  `Posting.weight()`, and there is no chance of the column disagreeing with the code.
- **(b) Store at write time.** The column is written once, when the posting is written.

Picked **(b)**, for two reasons that (a) cannot offer.

The balance invariant becomes checkable by anyone with a psql prompt and no
knowledge of the application: `SELECT txn_id, weight_currency, SUM(weight_minor)
FROM posting GROUP BY 1, 2 HAVING SUM(weight_minor) <> 0` either returns nothing or
names the broken transaction. For a tool whose output is an argument with a broker,
being auditable from outside the code matters more than avoiding a duplicated column.

And a change to the rounding rule cannot retroactively restate the weight of a
posting that was already written. Under (a) it silently would, and a historical
transaction that balanced when it was recorded could start failing years later for
reasons unrelated to its own data.

The cost of (b) is that the column could drift from the code. That is what the
`weight_minor` reconciliation in the persistence tests is for: every posting read
back is checked against a freshly computed `Posting.weight()`.

## 14. A zero plug is omitted, unless it is the only thing stating the other side

Found by the generated histories rather than by design, which is the argument for
generating them.

A plug posting of zero is normally dropped, so that a sale at exactly its cost
basis has no `Income:CapitalGains:Realized` leg instead of a `0.00` one. That rule
is right until it is the only rule. A purchase of `0.00000001` shares at a unit
price of `0.000001` has a total consideration of `0.00000000000001`, which is zero
in whole cents. Its commission is zero too, so the transaction is a single share
posting weighing nothing, and a single posting is not a double entry.

Options:

- **(a) Always emit the plug.** Every transaction has both sides, at the cost of a
  `0.00` gain leg on every sale that happens to break even.
- **(b) Reject the event.** An acquisition whose consideration rounds to nothing
  cannot be represented, so refuse it at the boundary.
- **(c) Emit the zero plug only when the transaction would otherwise have fewer
  than two postings.**

Picked **(c)**. It keeps the clean output of (a) without its noise, and it avoids
(b)'s real problem: an acquisition genuinely can cost nothing. A gift, an
inherited position, a promotional share grant and the receiving side of a spin off
are all zero consideration acquisitions that a broker will report and that the
week 3 corporate action work has to be able to record. Refusing them to avoid a
rounding edge case would be trading a cosmetic problem for a correctness one.

Stated as a rule: a transaction always states both sides. The plug is omitted only
when the transaction already states both sides without it.

## 15. What each invariant actually catches, and what it does not

Written down because two of the eight invariants are weaker than they look, and a
future reader deciding whether a change is safe needs to know which ones are
load bearing.

Established by mutation: three deliberate bugs were introduced one at a time and
the suite was run to see which properties noticed.

| Mutation | Caught by |
| --- | --- |
| realized gain sign flipped in the projection | invariant 5, plus both worked examples |
| cash postings stop updating positions | invariant 6, and the fee property |
| posting weight rounded HALF_UP instead of HALF_EVEN | invariant 6 only |

The third row is the useful one. A change to the rounding mode is **not** caught by
invariant 5, and that is not a gap in the test, it is a property of the invariant.
Once invariant 1 holds, defining proceeds as "every posting that is neither a
disposal nor a gain leg" makes `proceeds - basis = gain` algebraically forced. The
identity is real, and enforcing it as a CHECK constraint still catches a projector
that mis-attributes a leg, but it cannot catch an arithmetic rule applied
consistently to both sides.

What catches that is the cross-check in the invariant 5 property, which asserts
that recorded proceeds equal `Sell.grossProceeds()` recomputed from the event, and
invariant 6, whose expected cash is accumulated from the events with nothing the
ledger produced. Those two are the ones that would fail if the arithmetic changed.
Keep them independent of the ledger. The moment either starts deriving its
expectation from a posting, rounding changes become invisible.

### Invariant 4 is an identity at a point in time, not a conservation law

Total basis equals the sum over open lots of quantity times unit cost, which is
how the mandate states it and how it is implemented. It is worth being explicit
that this does **not** extend to basis conservation across a partial disposal,
because per posting rounding is not additive:

```
lot of 3 units at unit cost 0.005000
  original basis    round(0.015000) = 0.02
  disposed basis    round(0.005000) = 0.00   (HALF_EVEN, 0 is even)
  remaining basis   round(0.010000) = 0.01
  disposed + remaining = 0.01, but original was 0.02
```

So `remaining basis != original basis - disposed basis` in whole minor units. This
is inherent to lot accounting over fractional shares at sub cent unit costs, and
it is why invariant 4 is stated as an identity between the basis figure and the
open lots at one instant rather than as a running total.

The alternative is to stop recomputing remaining basis from quantity times unit
cost and instead track it explicitly in minor units, decrementing it by each
disposal's rounded basis. That is what a broker's cost basis engine does, and it
would make basis conservation exact. It was not taken here because the mandate
states invariant 4 in terms of quantity times unit cost, and because it adds a
field whose only job is to absorb rounding residue.

Week 3 should revisit this. Corporate actions restate unit costs across every open
lot of a position, and a split that has to preserve total basis exactly is the
first thing that will care about the residue above.

## 16. A disposal realizes a gain only when it was settled

Week 1 recorded a realized gain for every transaction containing a negative
security posting. Week 2 broke that, and correctly: a securities transfer disposes
of lots in one account and opens them in another, and reporting a realized gain
for it would invent a taxable event out of paperwork.

Options:

- **(a) Decide by event type.** The `Sell` handler records a gain, the `Transfer`
  handler does not.
- **(b) Decide from the postings.** A disposal realizes something only when the
  transaction also moved cash.
- **(c) Decide by whether the same commodity is re-acquired.** A transaction that
  both disposes of and acquires AAPL has reallocated it, not sold it.

Picked **(b)**. (a) is impossible: the projector rebuilds derived state from the
`posting` table, where no event survives, so a rule it cannot express is a rule
that makes replay disagree with the live ledger. Between (b) and (c), (b) is the
one that generalises: a reverse split paying cash in lieu of a fractional share
re-acquires the same commodity, so (c) would call it non-taxable, and it is
taxable on exactly the fraction that was paid out.

The rule is therefore: a disposal realizes a gain when the transaction contains a
cash posting. A realized gain leg counts, being a currency posting like any other,
which matters for a disposal whose proceeds round to nothing in whole minor units
but which still books a loss equal to its basis.

Known limit, recorded rather than papered over. "Cash moved" is exact for every
event that exists today because no securities transfer carries a fee. Week 3 has
to sharpen it twice: cash in lieu settles only the fraction rather than the whole
position, and a transfer that charges a fee would move cash without settling
anything.

## 17. A securities transfer carries its lots, with new identifiers

A transfer emits two postings per lot moved: one disposing of it in the sending
account, one opening it in the receiving account at the same unit cost and the
same acquisition date. Both weigh identically at cost, so the transaction balances
with no plug and no gain.

The acquisition date is the whole point. If the receiving lot were dated the day
of the transfer, the holding period would restart and every later disposal would
report a short term gain that was actually long term. That is why `Transfer` was
never a week 1 event despite looking like the simplest one in the list.

The receiving lot needs its own identifier, because a lot is opened by exactly one
acquisition and the outgoing lot still exists in the sending account's history.
It is derived by hashing the event's idempotency key together with the source lot
id, which keeps it stable across replays, fixed in length however many times a
position moves, and traceable back to where it came from. Concatenating instead of
hashing would grow the identifier without bound across repeated transfers.

Two smaller decisions:

- **The lot selection method is stated on the event**, not defaulted inside the
  handler. Which lots leave an account changes every gain later computed in both
  accounts, and a choice that matters that much belongs in the record that gets
  replayed rather than in a line of code someone can change. Specific lot is
  rejected: the event has nowhere to name lots.
- **Cash always lives at `<account>:Cash`.** A bank deposit is then just a
  transfer from `Assets:Bank:Chase` to `Assets:Broker:IBKR`, with no separate
  concept of an external account and no special case in the handler.

## 18. A dividend books gross income and withholding separately

`Income:Dividends:<SYMBOL>` takes the gross, `Expenses:Taxes:Withholding` takes
what was withheld at source, and the net is the cash plug. Netting them into a
single income leg would be smaller and would lose the thing a reconciliation needs
most: the reason the cash a broker paid is smaller than the dividend the issuer
declared.

Income is booked per commodity for the same reason. "You received 340.00 in
dividends" is not something anyone can argue with a broker about. "You received
12.00 from KO on this date" is.

Withholding is an expense rather than a prepaid tax asset. basis is not a tax
product, so it has nowhere to eventually apply a credit from, and calling withheld
tax an asset would imply a recoverability this ledger has no way to assess.

## 19. A corporate action re-lots the position, and the residue is booked

A split, a reverse split and a stock dividend all do one thing: restate a share
count without changing what the position is worth. They share one implementation,
`Relotting`, so the three cannot drift apart.

### Dispose and reopen, rather than mutate

A lot is immutable and `posting` is append-only, so a restatement disposes of each
open lot in full and reopens it at the new quantity. That keeps the whole change
expressible as postings, which is what lets the projector rebuild it and what
keeps invariant 7 true. Mutating a lot in place would put state in the derived
tables that the ledger could not reproduce.

Each reopened lot carries the original acquisition date. This is not a detail: a
split does not restart a holding period, and dating the new lot to the split would
turn a long term gain into a short term one on every later disposal. It is asserted
directly, both on a worked example and as a property over generated positions.

### The restated unit cost comes from the basis, not from the ratio

Two ways to compute the new unit cost:

- **(a) Divide the old cost by the ratio.** `150.00 / 4 = 37.50`. Obvious, and
  wrong in general: the quotient is rounded to six places first, and that error is
  then multiplied by the new share count. A large position loses basis for a reason
  nobody can point at.
- **(b) Divide the lot's basis by the new quantity.** The number that has to
  survive is the basis, so make the basis the input.

Picked **(b)**. It puts the single unavoidable rounding where it does the least
damage, and it makes the common case exact.

### The residue is booked to equity, not absorbed

Even under (b), six decimal places of unit cost cannot always reproduce a basis to
the cent. The error is bounded by `quantity * 5e-7` dollars plus half a cent per
lot, so it is invisible below about ten thousand shares and real above it. Two
million shares at `0.007777`, split three for one, leaves a basis of `15554.00`
spread over six million shares: `0.002592333...` per share, of which scale 6 holds
`0.002592`, and the missing third of a millionth multiplies up to exactly `2.00`.

Options:

- **(a) Let the position's basis absorb it.** Simplest, and it makes a split
  silently change what a position cost. For a tool whose entire output is an
  argument with a broker about cost basis, that is the worst possible failure: it
  manufactures a break out of arithmetic.
- **(b) Fragment the lot**, splitting the restated shares into two lots whose costs
  sum exactly to the basis. Exact, and it destroys lot identity, which specific-lot
  disposal depends on.
- **(c) Track basis in minor units on the lot** and make unit cost a derived,
  display-only value. Exact, no residue at all. This is what a broker's cost basis
  engine does. It also contradicts the mandate's phrasing of invariant 4 and adds a
  field whose only job is absorbing rounding.
- **(d) Book the residue to `Equity:Rounding:CorporateActions`** as the
  transaction's plug.

Picked **(d)** for now. It keeps every cent inside the ledger, visible and
queryable, and it makes invariant 8 exactly true as a conservation statement:
`basis after + residue booked = basis before`. In the ordinary case the residual is
zero and no posting is emitted at all.

**(c) is the better long term answer** and this decision should be revisited if
residues ever show up in real reconciliations. It is the same fix section 15
already identified for basis conservation across partial disposals, and doing it
once would close both gaps. It was not done here because it is a domain-wide change
and the mandate states invariant 4 in terms of quantity times unit cost.

### Two smaller decisions

**A stock dividend is a ratio, not a new lot.** Receiving 20 shares on a holding of
80 is a 100 for 80 restatement. Booking the free shares as their own zero cost lot
would be simpler and would report the wrong basis on every later partial disposal,
because free shares do not add basis, they dilute the basis already there.

**A forward split must increase the count and a reverse split must reduce it**,
enforced in the constructors. The arithmetic is identical either way, so nothing
technical requires the distinction. What requires it is that a parser mapping a
1 for 8 reverse split onto `Split` would produce a share count wrong by a factor of
64 and no test would notice. Refusing it at construction is cheaper than detecting
it later.

### What is still missing

A reverse split that leaves a fractional share is left holding the fraction. Real
brokers pay cash in lieu, which is a taxable disposal of that fraction and is
frequently not labelled as one on the statement. `ReverseSplit` has no field for
the cash paid, so this cannot be booked yet. The settlement rule in section 16 is
already shaped to handle it correctly once the field exists: cash reaching a real
cash balance is what makes a disposal realize something, and cash in lieu does
exactly that.

`SpinOff` remains unhandled. It is the only event that allocates basis across two
commodities, and the allocation fraction is published by the issuer rather than
derivable from any price feed, which is why it is the event most likely to raise a
break and ask rather than guess.
