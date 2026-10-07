# 0009. Review commands and processing workers share durable fences

Status: accepted, 2026-10-07

## Decision

Review mutations require a tenant-scoped `Idempotency-Key`. The key claim, fact transition,
review decision, and outbox event run in one database transaction. An identical request replays the stored response; a key reused for a different command returns a conflict. The API records its configured authenticated reviewer as the actor;
the request body cannot impersonate another reviewer.

Processing jobs are claimed with PostgreSQL `FOR UPDATE SKIP LOCKED`. Every checkpoint, failure,
and completion update checks tenant, `lease_owner`, a monotonically increasing `lease_generation`, RUNNING status, and lease expiration. Retry resets attempts but never the generation. An expired worker
therefore cannot commit after another worker has reclaimed the job. Stage functions remain
idempotent and a restart resumes after the last durable checkpoint.

## Consequences

The review response is durably recorded in the outbox and a duplicate request is
safe to retry. A crashed worker may repeat the stage that was running when it died, so stage
outputs must continue to use deterministic keys. The shipped implementation does not claim
external broker delivery or production deployment; the outbox remains the local durable handoff.
