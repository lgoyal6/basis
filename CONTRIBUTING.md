# Contributing

Thanks for looking. This is a ledger, so it has a few rules that are stricter than most
projects' and one that is unusual. They are all here.

## Getting a working copy

You need JDK 21 and Docker. Docker is not optional: the persistence and reconciliation
tests start a real Postgres 16 through Testcontainers, because a ledger tested against an
in-memory database is tested against different rounding, different types and different
constraints than the one it will run on.

```
git clone https://github.com/lgoyal6/basis
cd basis
./gradlew test
```

That is the whole setup. The suite takes a couple of minutes, mostly waiting for containers.

To run the binary against a database of your own:

```
docker compose up -d
./gradlew bootJar
java -jar build/libs/basis.jar --help
```

## The rules

**No `double` or `float`. Anywhere.** Money is `long` minor units, quantities are
`BigDecimal` at scale 8, prices at scale 6. An ArchUnit test fails the build if a
floating point type appears in a declaration or in a method you call. This is not a
style preference; binary floating point cannot represent `0.10`, and a cost basis that
drifts by a cent per trade is wrong in a way nobody notices until a tax year.

**Realized gain is derived, never computed.** It is the plug that makes a disposal
balance. There is exactly one place a gain can come from, so there is exactly one thing
to get right. A pull request that adds a second way to arrive at a gain will be asked to
remove it, even if the two agree today.

**The `posting` table is append-only.** Positions, lots and realized gains are derived
and can be truncated and rebuilt at any time. Nothing may update or delete a posting. If
you need to undo something, the ledger's answer is a compensating entry, which is also
what an accountant's answer is.

**Nothing guesses.** If a statement row is ambiguous, the import stops and says exactly
what it could not read. If a break's cause is arithmetic rather than evidence, it is
marked not confident and says why. "Checked and found nothing", "never checked" and
"could not check" are three different answers and the code keeps them apart. Any change
that turns one of those into a silent default is the wrong change.

**Corporate actions are asserted, not parsed.** They are entered with `apply` or come
from reference data. A transaction export reports them inconsistently and often not at
all, and a split read wrongly restates every lot in a position.

## The unusual one

**If a design decision is ambiguous, write the options into `docs/ARCHITECTURE.md` and
pick one, saying why.** Do not decide silently.

That file is not documentation written after the fact. It is the record of every fork in
the road this project has come to, including the ones where the choice was close and the
ones that turned out wrong. Twenty-nine sections so far. If you find yourself thinking
"there are two reasonable ways to do this", that thought is the deliverable: write both
down, choose, and say what would make you choose differently.

## Tests

New behaviour needs a test. That is ordinary. Two things about this repo are not:

**Prove your test can fail.** The suite has eight invariants asserted over generated
histories, and invariants are easy to write so loosely that they pass for the wrong
reason. Before trusting a new one, break the code on purpose and check it catches you.
Several tests here carry a comment naming the bug they were mutation-tested against.

**Test what is true, not what is said.** A real example, from this repo's history: a test
asserted an error message contained `basis apply cash-in-lieu`. It passed for weeks. There
has never been such a command, so the message sent everyone to a usage error. Asserting
the string appeared was not the same as asserting it was right. There is now a test that
holds every command named in that advice against the list the CLI actually dispatches on.

Tests tagged `network` call a market data provider for real and are excluded from every
default run. Run them with `./gradlew test -PwithNetwork` and a key in the environment.

## Commits and pull requests

- One commit per logical change, each one building and testing green on its own. If a
  branch has grown into a single "did the thing" commit, split it before opening the PR.
- Conventional prefixes: `feat:`, `fix:`, `docs:`, `chore:`, `test:`.
- Say what you verified, not that it should work. If you could not verify something, say
  that instead; "unverified" is a fine thing to write and a bad thing to leave implied.
- CI runs the full suite on every pull request.

## Adding a broker

You almost certainly do not need to write Java. A broker is a properties file in
`config/brokers/`, and no broker is named anywhere in the source. Copy
`fidelity.properties`, change the column names and action phrases, and import something.

A row basis cannot read stops the import and prints the exact wording it did not
recognise, which is how you find out what to add. One exception, stated in that file and
worth repeating: do not add corporate action phrases to make an import proceed. They are
left out on purpose, because the row records that a merger happened and never on what
terms, and importing it would restate a cost basis nobody stated.

A profile validated against a real export from a broker nobody here has an account with
is a genuinely useful contribution. Say in the pull request whether you ran it against
real data or wrote it from the format's documentation, because those are different
claims and the file says which for every phrase.
