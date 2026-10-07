# 0002. Persistence model for documents, facts and review

Status: accepted, 2026-10-06

## Context

The product promise is that every displayed value can be traced to its source, its
transformation, its confidence and its review history. That is a statement about the schema
before it is a statement about the UI: if a correction overwrites a raw value, or a stage writes
into a row another stage already wrote, the trace is gone and no view can bring it back.

The existing ledger uses Spring `JdbcClient` against Postgres 16 with Flyway, no ORM
(ARCHITECTURE.md section 0). The document model follows the same rule.

## Decision

New migrations add these tables. Every one carries `tenant_id`.

| table | role | mutability |
| --- | --- | --- |
| `doc_tenant`, `doc_api_token` | who may see what | tokens revoke, never delete |
| `document` | a logical document (a filing, a workbook) grouped by `document_key` | metadata only |
| `document_version` | one uploaded byte stream, identified by SHA-256 | immutable except `status` and purge columns |
| `document_page`, `document_table` | layout artifacts per version | immutable, purgeable |
| `stage_artifact` | one row per (version, stage, stage version), with input and output hashes | immutable |
| `extraction_run` | one pipeline execution, with extractor, model and prompt versions | append, then closed |
| `extracted_field` | a raw value exactly as read, with its location | **immutable** |
| `extraction_evidence` | where in the source the raw value sits (page/bbox, sheet/cell, XBRL fact) | immutable |
| `normalized_fact` | a typed value derived from a field, or a reviewer correction | value columns immutable; `status` moves through the state machine |
| `fact_mapping` | versioned rules mapping labels and XBRL concepts to fact types | append new versions |
| `reconciliation_case` | a group of facts compared under one key, with classification and explanation | versioned (`version` column) |
| `review_task`, `review_decision` | the queue and every decision taken | decisions append-only |
| `audit_event` | hash-chained, append-only log of every state change | **UPDATE and DELETE blocked by trigger** |
| `processing_job`, `outbox_event`, `idempotency_key` | reliable asynchronous processing | see [0003](0003-pipeline-jobs-leases-outbox.md) |

### Rules the schema enforces, not the code

1. **A correction is a new row.** `normalized_fact.supersedes_fact_id` points at the fact it
   replaces; the old row keeps its value and moves to `SUPERSEDED`. A trigger rejects any
   UPDATE that changes a value column (`value`, `raw_value`, `unit`, `currency`, period
   columns) of an existing fact.
2. **A raw extraction cannot be edited.** `extracted_field` and `extraction_evidence` reject
   UPDATE outright.
3. **Every fact stores every attribute the prompt lists**: raw value, normalized value, unit,
   currency, reporting period, source document and version, page/table/row/cell location,
   extraction method, confidence, model and prompt version (nullable, because no shipped
   extractor uses a model), status, and the reviewer decision via `review_decision`.
4. **Money is `NUMERIC(38,6)` with an explicit precision exponent**, never a float. The
   exponent records what the source actually stated (reported in millions with no decimals is
   exponent 6), which the reconciliation tolerance depends on
   ([0004](0004-reconciliation-classification.md)).
5. **Tenant isolation is enforced by Postgres row level security** with `FORCE ROW LEVEL
   SECURITY`, so even the table owner the app connects as sees nothing without
   `basis.tenant_id` set for the transaction. Application queries also filter by tenant; RLS is
   what makes a forgotten filter a test failure instead of a leak. `processing_job` and
   `outbox_event` carry ids only and are claimed across tenants by the worker, so they are the
   two tables without a policy; the worker switches into the job's tenant before it reads any
   content.

## Consequences

- A fact's full history is a join, not a reconstruction: field, evidence, normalization steps
  (JSON on the fact), reconciliation explanation, decisions, audit events.
- Deleting a document cannot be a cascade, because review decisions and the audit chain must
  survive it. Deletion is a purge of content columns and blobs with a tombstone; see
  [0006](0006-retention-deletion-and-audit.md).
- The ledger tables (`V1`-`V8`) are untouched. No existing migration is edited.
