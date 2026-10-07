# 0006. Retention, deletion, and an audit trail that survives both

Status: accepted, 2026-10-06

## Context

Two requirements pull against each other: a document must be deletable (retention limits, a
customer's request), and the audit trail must be immutable and reproducible after restart.

## Decision

### The audit log is append-only and hash-chained

`audit_event` rows carry `prev_hash` and `hash = sha256(prev_hash || canonical_json(event))`,
chained per tenant. A trigger rejects `UPDATE` and `DELETE`. `GET /v1/audit/verify` (and
`basis docs audit-verify`) walks the chain and also **replays** the fact status transitions it
records, comparing the result with the stored `normalized_fact.status`; a mismatch names the
first divergent event. A database superuser can still disable the trigger, which is why the
chain exists: tampering becomes detectable rather than impossible.

Every state change writes an event in the same transaction as the change: ingest, stage
completion, fact status changes, case classification changes, review decisions, job retries and
cancellations, deletion, export (with row count and content hash of what was exported).

### Deletion is a purge with a tombstone

`DELETE /v1/documents/{id}` (reviewer role) records the request and enqueues a purge job. The
purge:

- deletes the encrypted blob for each version;
- nulls page text, table cells, evidence snippets and stage artifact payloads;
- marks every fact from the document `SOURCE_DELETED`, recomputes affected reconciliation
  cases, and cancels open review tasks;
- keeps the version row (hash, size, filename, timestamps) as a tombstone.

Audit events are **not** purged. They contain fact values, labels and locations, but never page
text or file contents. That is the documented trade: an auditor can prove what was decided and
why, without the system retaining the document itself.

### Retention is configurable per tenant

`doc_tenant.retention_days` (default from `basis.documents.retention-days`, 365). A scheduled
sweep enqueues purge jobs for versions older than that. Approved exports already downloaded are
outside the system's control and the runbook says so.

## Consequences

- "Reproduce the audit trail after restart" is a test: restart the context, verify the chain,
  replay statuses, compare.
- A purge is idempotent and resumable like every other job.
