# 0001. The document workbench is a separate mode, not an extension of `serve`

Status: accepted, 2026-10-06

## Context

basis already has two shapes: a CLI over an append-only ledger, and `basis serve`, a public
web app whose whole promise ([PRIVACY.md](../../PRIVACY.md)) is that an uploaded statement is
held in memory for two hours and is **never written to a database**.

Document reconciliation needs the opposite: durable storage of originals, extracted facts,
review decisions and an audit trail that outlives a restart, plus authenticated tenants. Putting
that into `serve` would silently break the privacy promise that `serve` is built on and that its
tests (`RetentionTest`, `SessionIsolationTest`, `ApiContractTest` counting ten operations)
pin.

## Decision

A new command, `basis workbench`, starts a servlet application under the Spring profile
`workbench`. Only document controllers, the pipeline worker and the outbox relay load under
that profile. `serve` keeps the `web` profile and is unchanged: none of the new beans load
there, and its contract test still sees exactly its ten operations.

The document code lives in its own package tree, `com.basis.documents`, split the way the build
workflow asks:

| package | owns | may depend on |
| --- | --- | --- |
| `documents.domain` | documents, fields, evidence, normalization, reconciliation, review rules | JDK only |
| `documents.application` | ingest, pipeline stages, reconcile, review, export use cases; ports | domain |
| `documents.adapters.storage` | Postgres repositories, encrypted object store, signed URLs | application, domain |
| `documents.adapters.extraction` | CSV, XLSX, XBRL, PDF, OCR | application, domain |
| `documents.workers` | job claiming, leases, retry, outbox relay | application |
| `documents.api` | authenticated JSON HTTP interface | application |
| `documents.web` | server-rendered views over the same use cases | application |

`DocumentArchitectureTest` enforces the domain row with ArchUnit; the domain imports no Spring,
JDBC, PDFBox or servlet types.

It shares the ledger's Postgres database and Flyway history (one migration sequence, one
`clean-disabled` guarantee) but none of its tables. The ledger and the document workflow do not
read each other's data; connecting a reconciled financial fact to a ledger position is out of
scope and listed under limitations.

## Consequences

- `serve` stays a no-account, no-database surface. Its privacy page stays true.
- The workbench is a second deployable shape of the same jar. It needs its own secrets
  (`BASIS_DOCS_KEY`), its own storage volume, and authentication, all described in
  [0005](0005-tenancy-auth-and-object-storage.md).
- Two OpenAPI documents: `docs/openapi.json` for `serve` (unchanged) and
  `contracts/documents-openapi.json` for the workbench, each checked against its own live
  handler mapping.
