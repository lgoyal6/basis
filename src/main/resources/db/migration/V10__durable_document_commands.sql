alter table idempotency_key add column request_hash text;
alter table idempotency_key add column response_json jsonb;
create table document_stage_artifact (
    job_id uuid not null references processing_job(id),
    stage text not null,
    payload jsonb not null,
    created_at timestamptz not null default now(),
    primary key (job_id, stage)
);
alter table processing_job add column lease_generation bigint not null default 0;
