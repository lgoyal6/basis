# 0004. Reconciliation: deterministic keys, precision-aware equivalence, nine classes

Status: accepted, 2026-10-06

## Context

The dangerous output of a reconciliation tool is a false agreement: two numbers shown as "the
same" when they are not, because one was in thousands and the other in millions, one was a
quarter and the other year to date, or one was rounded so coarsely that it would "match"
anything nearby. basis's ledger already treats "checked and found nothing", "never checked" and
"could not check" as different answers. The document reconciler follows the same rule.

## Decision

### Matching key

Facts are grouped by `(issuer, fact_type, context, period_end)`:

- `issuer` comes from upload metadata or a structured identifier (XBRL entity); a fact with no
  issuer cannot be grouped and is `UNSUPPORTED_COMPARISON`.
- `fact_type` comes from a versioned `fact_mapping` rule (label pattern or XBRL concept).
  Unmapped fields are extracted and shown, but never reconciled.
- `context` is `consolidated` or a segment/section path (`segment:americas`). This is what keeps
  two "Revenue" rows in different sections from being compared.
- `period_end` is the end date (or instant). Duration and start date are compared inside the
  group, which is how a period mismatch is detected rather than silently split into two groups.

Normalization is deterministic and happens before any comparison: parentheses and unicode minus
to negative, thousands separators, scale words (`in thousands`, `in millions`, XBRL `decimals`),
currency symbols and codes, fiscal period labels.

### Classes, checked in this order

| order | class | rule |
| --- | --- | --- |
| 1 | `UNSUPPORTED_COMPARISON` | a side has no numeric value, no issuer, or a monetary fact with no currency |
| 2 | `PERIOD_MISMATCH` | same end, different start or duration, or instant vs duration |
| 3 | `UNIT_MISMATCH` | different currency, or monetary vs shares vs per-share vs percent |
| 4 | `AMENDED_VALUE` | values differ and one source is a later version of, or declared amendment to, the other |
| 5 | `LOW_CONFIDENCE_MATCH` | values agree but either side is below the confidence threshold (OCR, unresolved header) |
| 6 | `EXACT_MATCH` | normalized values equal and raw text equal |
| 7 | `EQUIVALENT_AFTER_NORMALIZATION` | normalized values equal within the coarser side's stated precision |
| 8 | `CONFLICTING_VALUE` | anything else that disagrees |
| - | `MISSING_VALUE` | a source covering this issuer and period reports other facts but not this one |

A group's classification is its worst pairwise class.

### Precision-aware equivalence, and why it is strict

`383,285` reported "in millions" and `383,285,000,000` reported in units are equal after scale.
`$383.3 billion` matches them only because the finer value **rounds to** the coarser one at the
coarser precision (half-up at `0.1 billion`). The rule records that precision in the
explanation. Two values that are both coarse but differ, or where the finer one does not round
to the coarser one, are a conflict, not a match. Equivalence is never granted on a ratio or a
percentage difference.

### Every classification explains itself

The stored explanation is JSON with the rule id and version, a one-sentence summary, and for
each side: raw value, normalized value, unit, currency, period, source document, version hash,
location, extraction method and confidence. The UI renders this, and so does the export.

## Consequences

- False agreement is a measured quantity in the evaluation harness
  (`docs/evidence/documents-eval.md`), with fixtures built to tempt it: scale mismatches,
  rounding near-misses, quarter vs year-to-date.
- Semantic matching (unmapped labels) is a separate, optional, post-validated path; see
  [0007](0007-model-assisted-matching.md).
