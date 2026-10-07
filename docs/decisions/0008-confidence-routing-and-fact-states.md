# 0008. Confidence is a named-check score, routing is a rule, and facts have a state machine

Status: accepted, 2026-10-06

## Confidence

Confidence is an integer in permille (0-1000), computed from named checks, and every fact stores
the checks that moved it. It is an **ordinal routing score, not a calibrated probability**, and
the UI labels it that way. Starting at 1000:

| check | penalty | why |
| --- | --- | --- |
| `OCR_TEXT` | -400 | characters came from OCR, not a text layer |
| `PERIOD_UNRESOLVED` | -300 | no column header resolved to a period |
| `HEADER_AMBIGUOUS` | -200 | the column header was resolved from a merged or multi-line header by position only |
| `CURRENCY_INFERRED` | -50 | currency came from a `$` symbol rather than a code |
| `SCALE_UNSTATED` | -50 | a PDF table with no "in thousands/millions" statement; values taken as stated |

The review threshold is 800. XBRL facts start at 1000 and lose nothing: their period, unit and
precision are declared, not inferred.

## Routing

A fact is routed to review (`NEEDS_REVIEW`, task priority `ATTENTION`) when any of these hold:

| reason | condition |
| --- | --- |
| `LOW_CONFIDENCE` | confidence < 800 |
| `OCR_SOURCE` | extraction method is OCR, regardless of score; OCR never stands on its own |
| `PROVENANCE_UNVERIFIED` | the raw value is not found at its recorded location in the stored artifact |
| `NORMALIZATION_INCOMPLETE` | no numeric value, no period, or a monetary fact with no currency |
| `RECONCILIATION_<CLASS>` | its case is anything other than exact or equivalent |
| `MODEL_SUGGESTION` | the fact type came from the suggestion port |

Everything else is `VERIFIED` and gets a `CONFIRM` task. Nothing is exported until a reviewer
approves it; verification orders the queue, it does not replace the reviewer.

## Fact states

```text
NORMALIZED ──validate──> VERIFIED ──review──> APPROVED
     │                      │  ▲                 │
     └──────> NEEDS_REVIEW <┘  │(never)          ├──correct──> SUPERSEDED (+ new APPROVED fact)
                   │                             │
                   ├──approve──> APPROVED        └── source unusable / deleted
                   ├──reject───> REJECTED
                   ├──correct──> SUPERSEDED (+ new APPROVED fact linked by supersedes_fact_id)
                   └──merge────> MERGED (merged_into_fact_id)

any non-terminal ──source unusable──> SOURCE_UNUSABLE
any               ──purge────────────> SOURCE_DELETED
```

`FactStatus.canTransitionTo` is the single definition, and the storage layer refuses an update
the domain did not allow. A reconciliation that later finds a conflict can move `VERIFIED` to
`NEEDS_REVIEW`, never the reverse: once a human has been asked, only a human closes it.

Review decisions carry the task's `version`; a decision against a stale version is a 409 with
the current state, so two reviewers cannot both act on what they each believed was the latest
value.
