# Document workbench runbook

The document workflow runs under the `workbench` Spring profile. Keep it separate from
`basis serve`, whose upload data is intentionally memory-only.

Required configuration:

- `BASIS_DB_URL`, `BASIS_DB_USER`, and `BASIS_DB_PASSWORD` for PostgreSQL;
- `BASIS_DOCUMENTS_TOKEN_DIGEST`, the SHA-256 hex digest of the bearer token;
- `BASIS_DOCS_KEY` for encrypted document storage once the object-store adapter is enabled.

Start locally with Java 21 and the normal Gradle boot jar. Flyway applies the document schema
including tenant, version, job, fact, review, audit, outbox, and idempotency tables. Never run
Flyway clean against a ledger database.

Security checks:

1. Requests to `/v1/workbench/*` require `Authorization: Bearer ...` and `X-Tenant-Id`.
2. Store only token digests. Logs must never contain bearer values or document contents.
3. Reviewer actions require the reviewer role and an expected task version.
4. A duplicate idempotency key is rejected within a tenant and accepted independently for another tenant.
5. Keep the public `serve` profile disabled when operating the durable workbench.

Verification command:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  PATH="$JAVA_HOME/bin:$PATH" ./gradlew test --no-daemon
```

Known limitation: the current branch contains the application ports and schema, while the
PostgreSQL repositories, encrypted object-store adapter, and production worker deployment still
need to be wired before production use.
