-- Binds a review decision to its exact resulting fact for approved export.
-- JdbcReviewRepository stores the result when an approval or correction commits.
-- Historical corrections are recovered only from one unambiguous stored response.
alter table review_decision add column result_fact_id uuid references normalized_fact(id);
update review_decision set result_fact_id=fact_id where action <> 'CORRECT';
-- Recover historical corrections only from the stored atomic command response.
-- Missing or ambiguous historical evidence remains non-exportable.
update review_decision r set result_fact_id = (
    select (array_agg(distinct f.id))[1] from idempotency_key k join normalized_fact f
        on f.id::text=k.response_json->'fact'->>'id'
        and f.tenant_id=k.tenant_id and f.supersedes_fact_id=r.fact_id
    where k.tenant_id=r.tenant_id and k.response_json->'task'->>'id'=r.task_id::text
        and k.response_json->>'action'='CORRECT'
    having count(distinct f.id)=1
) where r.action='CORRECT';
