alter table document_version add column if not exists object_key text;
alter table document_version add column if not exists correlation_id text;
create index if not exists document_version_object on document_version(tenant_id, object_key);
