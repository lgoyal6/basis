# 0003. The pipeline is a durable job with a stage checkpoint, leases, and an outbox

Status: accepted, 2026-10-06

## Context

Extraction is slow relative to a request (OCR is seconds per page) and fails in two different
ways: transiently (a subprocess timed out, the database blinked) and permanently (the file is
malformed). Both must be visible, retryable where retrying makes sense, and recoverable after
the process dies at any point.

## Decision

### One job per document version, one checkpoint per stage

Ingest commits the immutable original and a `processing_job` row in one transaction. A worker
runs the stages in order:

```text
classify -> layout (text, OCR) -> tables -> candidates -> normalize
         -> validate (provenance) -> reconcile -> route (review queue)
```

Each stage commits, in a **single transaction**: its typed output rows, a `stage_artifact` row
`(version, stage, stage_version, input_hash, output_hash)`, the job's `checkpoint`, and an
`outbox_event`. Either all of that exists or none of it does. A restarted job skips any stage
whose artifact exists for the same stage version and input hash, and records which stages were
reused in `extraction_run.reused_stages`, so a rerun explains itself.

Bumping a stage's version (because its code changed) invalidates its artifact and every later
stage's, because each later stage's input hash includes the earlier output hash.

### Leases with fencing

Workers claim with `FOR UPDATE SKIP LOCKED`, set `lease_owner`, `lease_expires_at`, increment
`attempt`, and heartbeat between stages. Every stage commit is conditional on
`lease_owner = me AND attempt = my attempt`; a worker whose lease expired and was reclaimed
cannot commit, so two workers can never both write a stage. A crashed worker's job becomes
claimable again when its lease expires.

### Retry classes

| class | examples | behaviour |
| --- | --- | --- |
| `TRANSIENT` | OCR timeout, I/O error, serialization failure | back off `base * 2^(attempt-1)` up to `max_attempts`, then `DEAD` |
| `PERMANENT` | malformed PDF, zip bomb, unsupported content | `FAILED` immediately, partial artifacts kept, actionable error stored |
| cancellation | operator asked | `CANCELLED` between stages, artifacts kept |

`DEAD` is the dead-letter state. `POST /v1/jobs/{id}/retry` (or the review action "request
reprocessing") requeues a `FAILED`, `DEAD` or `CANCELLED` job with a fresh attempt budget; the
completed stages are reused.

### Outbox

Events are written in the stage transaction and delivered by a relay that marks
`published_at`. The shipped sink is the structured log plus metrics; the processing timeline
view reads the outbox directly. There is no external broker, because nothing consumes one yet
(build workflow rule 10). Delivery is at-least-once and events carry an id for deduplication.

### Validation at the door, not in the worker

Format detection, size limits and decompression-bomb checks run synchronously at ingest, so an
unsupported file is rejected with a 415/413/422 and an actionable message instead of being
accepted and failing silently in a worker. The worker re-checks (defence in depth) and fails
`PERMANENT` with the same message if anything slipped through.

## Consequences

- Restart at any stage is tested by `PipelineRestartTest`, which kills the pipeline before and
  after every stage commit and proves the result is identical and the reuse is reported.
- Exactly-once stage output, at-least-once event delivery.
- A single worker thread per process by default; more threads or processes are safe because
  of `SKIP LOCKED` and fencing, and a test exercises a stale worker losing its fence.
