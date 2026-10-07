-- Durable document workbench tables. Content and extracted values are append-only; review
-- changes are represented by new rows so the source-to-decision chain remains reproducible.
create table if not exists doc_tenant (
    id uuid primary key, name text not null unique, retention_days integer not null default 365,
    created_at timestamptz not null default now()
);
create table if not exists document (
    id uuid primary key, tenant_id uuid not null references doc_tenant(id), document_key text not null,
    issuer text, created_at timestamptz not null default now(), unique (tenant_id, document_key)
);
create table if not exists document_version (
    id uuid primary key, tenant_id uuid not null references doc_tenant(id), document_id uuid not null references document(id),
    sha256 text not null, filename text not null, media_type text not null, size_bytes bigint not null,
    status text not null default 'RECEIVED', created_at timestamptz not null default now(), unique (tenant_id, sha256)
);
create table if not exists processing_job (
    id uuid primary key, tenant_id uuid not null references doc_tenant(id), version_id uuid not null references document_version(id),
    idempotency_key text not null, status text not null default 'QUEUED', checkpoint text, attempt integer not null default 0,
    lease_owner text, lease_expires_at timestamptz, next_retry_at timestamptz, last_error text, correlation_id text not null,
    created_at timestamptz not null default now(), unique (tenant_id, idempotency_key)
);
create table if not exists extracted_field (
    id uuid primary key, tenant_id uuid not null references doc_tenant(id), version_id uuid not null references document_version(id),
    fact_type text, raw_value text not null, unit text, currency text, period_start date, period_end date,
    location text not null, method text not null, confidence integer not null, created_at timestamptz not null default now()
);
create table if not exists normalized_fact (
    id uuid primary key, tenant_id uuid not null references doc_tenant(id), extracted_field_id uuid references extracted_field(id),
    issuer text, fact_type text not null, context text not null, value numeric(38,6), raw_value text not null, unit text not null,
    currency text, period_start date, period_end date not null, source_document text not null, source_version text not null,
    location text not null, extraction_method text not null, confidence integer not null, status text not null,
    supersedes_fact_id uuid references normalized_fact(id), created_at timestamptz not null default now()
);
create table if not exists reconciliation_case (
    id uuid primary key, tenant_id uuid not null references doc_tenant(id), classification text not null,
    explanation jsonb not null, version integer not null default 1, created_at timestamptz not null default now()
);
create table if not exists review_task (
    id uuid primary key, tenant_id uuid not null references doc_tenant(id), fact_id uuid not null references normalized_fact(id),
    reason text not null, status text not null default 'OPEN', version integer not null default 0, created_at timestamptz not null default now()
);
create table if not exists review_decision (
    id uuid primary key, tenant_id uuid not null references doc_tenant(id), task_id uuid not null references review_task(id),
    fact_id uuid not null references normalized_fact(id), actor text not null, action text not null, reason text not null,
    previous_status text not null, new_status text not null, created_at timestamptz not null default now()
);
create table if not exists audit_event (
    id uuid primary key, tenant_id uuid not null references doc_tenant(id), event_type text not null, payload jsonb not null,
    prev_hash text, hash text not null, created_at timestamptz not null default now()
);
create table if not exists outbox_event (
    id uuid primary key, tenant_id uuid not null references doc_tenant(id), event_type text not null, payload jsonb not null,
    published_at timestamptz, created_at timestamptz not null default now()
);
create table if not exists idempotency_key (
    tenant_id uuid not null references doc_tenant(id), key text not null, response_hash text, created_at timestamptz not null default now(),
    primary key (tenant_id, key)
);
create index if not exists processing_job_claim on processing_job (status, next_retry_at, lease_expires_at);
create index if not exists facts_reconcile on normalized_fact (tenant_id, issuer, fact_type, context, period_end);
