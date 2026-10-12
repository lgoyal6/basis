-- Keep the two immutable facts that produced a comparison. The JSON explanation remains
-- human-readable, while these columns make reruns and audit queries deterministic.
alter table reconciliation_case add column if not exists left_fact_id uuid references normalized_fact(id);
alter table reconciliation_case add column if not exists right_fact_id uuid references normalized_fact(id);
create unique index if not exists reconciliation_case_pair
    on reconciliation_case (tenant_id, left_fact_id, right_fact_id);
