-- Records the current source while retaining immutable document versions.
-- DocumentIngestService advances it under the same tenant lock used by review.
-- Historical timestamp ties use UUID order and require operator review if material.
alter table document add column current_version_id uuid references document_version(id);
update document d set current_version_id = (
    select v.id from document_version v where v.document_id=d.id and v.tenant_id=d.tenant_id
    order by v.created_at desc, v.id desc limit 1
);
