# 0005. Tenancy, authentication, and encrypted object storage

Status: accepted, 2026-10-06

## Decision

### Tenants and tokens

A tenant is created from the CLI (`basis docs tenant-create <name>`), which prints one bearer
token per role exactly once. Only the SHA-256 of a token is stored. Roles:

| role | may |
| --- | --- |
| `uploader` | ingest, read documents, facts, cases, jobs; retry or cancel jobs |
| `reviewer` | everything `uploader` may, plus review decisions and approved export |
| `auditor` | read everything, verify the audit chain, export; no mutations to facts |

Every API request resolves a token to `(tenant, role)` before any handler runs. The tenant is
then set on the database transaction (`set_config('basis.tenant_id', ..., true)`), where row
level security limits every read and write to that tenant. A request for another tenant's id
returns 404, not 403, so ids are not an oracle.

The browser views use the same tokens: a sign-in form exchanges the token for an `HttpOnly`,
`SameSite=Strict` cookie holding it; there is no separate password system.

### Object storage

Originals are stored through an `ObjectStore` port. The shipped adapter is the local
filesystem, because the local quickstart and the tests must run without a cloud account (Mint
pattern: dry run when credentials are absent):

- **content addressed and write-once**: the key is `tenant/<sha256>`; writing opens with
  `CREATE_NEW`, so an existing original can never be overwritten;
- **encrypted at rest** with AES-256-GCM, using a per-tenant key derived by HMAC-SHA256 from
  `BASIS_DOCS_KEY`; the tenant id is the GCM associated data, so a blob copied into another
  tenant's directory fails authentication instead of decrypting;
- **signed URLs**: `GET /v1/versions/{id}/original` returns a URL carrying
  `exp` and an HMAC over `(tenant, object key, exp)`; the blob endpoint verifies the signature
  and expiry in constant time before reading anything.

An S3 adapter is not shipped. The port is three methods (`put`, `get`, `delete`), and the
limitations section says so.

### In transit

The workbench speaks plain HTTP inside its container, exactly like `serve`, and expects TLS at
the proxy (Railway, a load balancer, or the Caddy service in `infra/`). It sets
`Strict-Transport-Security` when the request arrived over HTTPS. The database connection's TLS
is controlled by `BASIS_DB_URL` (`sslmode=require` in production).

### Secrets

`BASIS_DOCS_KEY` and the database password come from the environment only. The log layout runs
every message through a redactor that masks bearer tokens, `basis_` prefixed token strings,
signed URL signatures, and the key itself; a test logs each of them and asserts none reach the
appender.

If `BASIS_DOCS_KEY` is unset the workbench refuses to start. It does not generate an ephemeral
key, because blobs written under a key nobody kept are unreadable after the next restart.
