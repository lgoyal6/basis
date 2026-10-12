create unique index if not exists review_task_fact_reason
    on review_task (tenant_id, fact_id, reason);
