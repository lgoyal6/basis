-- Alias retry keys to the original job, including content duplicates with new keys.
create table document_ingest_request (
    tenant_id uuid not null references doc_tenant(id),
    key text not null,
    request_hash text not null,
    job_id uuid not null references processing_job(id),
    primary key (tenant_id, key)
);
