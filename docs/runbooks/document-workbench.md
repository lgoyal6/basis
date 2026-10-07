# Document workbench runbook

The document workflow runs under the `workbench` Spring profile. Keep it separate from
`basis serve`, whose upload data is intentionally memory-only.

Required configuration:

- `BASIS_DB_URL`, `BASIS_DB_USER`, and `BASIS_DB_PASSWORD` for PostgreSQL;
- `BASIS_DOCUMENTS_TOKEN_DIGEST`, the SHA-256 hex digest of the bearer token;
- `BASIS_DOCUMENTS_TENANT_ID`, `BASIS_DOCUMENTS_ACTOR`, and `BASIS_DOCUMENTS_ROLE` bind the token to a tenant, reviewer identity, and role;
- `BASIS_DOCS_KEY` for encrypted document storage once the object-store adapter is enabled.

Start locally with Java 21 and the normal Gradle boot jar. Flyway applies the document schema
including tenant, version, job, fact, review, audit, outbox, and idempotency tables. Never run
Flyway clean against a ledger database.

Security checks:

1. Requests to `/v1/workbench/*` require `Authorization: Bearer ...` and `X-Tenant-Id`.
2. Store only token digests. Logs must never contain bearer values or document contents.
3. Reviewer actions require the reviewer role and an expected task version.
4. Identical commands replay by tenant-scoped idempotency key. A different command with the same key returns 409.
5. Keep the public `serve` profile disabled when operating the durable workbench.

Verification command:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  PATH="$JAVA_HOME/bin:$PATH" ./gradlew test --no-daemon
```

PostgreSQL review commands commit the fact transition, task version, stored response, and outbox event atomically. Corrections preserve the source fact and create a linked successor. `DurablePipelineWorker` uses the JDBC job repository with lease generation fencing. Stage JSON artifacts, checkpoints, and outbox events commit together; a restart begins after the last checkpoint. The tenant-scoped `artifacts` port lets resumed adapters read previously committed outputs. Cancellation fences existing workers, and explicit retry preserves checkpoints while resetting the attempt budget.

Stage adapters must be idempotent and finish within the configured lease duration. Long running adapters must extend the lease through the repository heartbeat. External side effects cannot be rolled back by the checkpoint transaction. No broker relay is shipped: outbox rows are a durable local handoff, not proof of delivery.

Known limitations: the encrypted object-store adapter, complete extraction adapters, and a continuously running production worker deployment are not wired. The worker persistence layer is tested against PostgreSQL; it is not a claim that every document format has an end-to-end ingestion workflow.
