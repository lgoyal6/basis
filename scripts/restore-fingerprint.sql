-- The fingerprint a restore has to reproduce: timestamps, extremes, and the
-- constraints the database is still enforcing.
--
-- Printed as one key/value column pair so the source and the target can be
-- diffed line by line. Timestamps are printed to the microsecond and with
-- their offset, because "the rows came back" and "the rows came back carrying
-- the instants they were written at" are different claims, and a restore that
-- re-defaulted a timestamptz to now() satisfies only the first.
\pset footer off
\t on
\a
\f '='

SELECT 'import_batch.count',        count(*)::text FROM import_batch
UNION ALL SELECT 'import_batch.started_at.min',  coalesce(min(started_at)::text, '-') FROM import_batch
UNION ALL SELECT 'import_batch.started_at.max',  coalesce(max(started_at)::text, '-') FROM import_batch
UNION ALL SELECT 'import_batch.committed_at.max', coalesce(max(committed_at)::text, '-') FROM import_batch
UNION ALL SELECT 'import_batch.content_hash.agg', coalesce(md5(string_agg(encode(content_hash, 'hex'), ',' ORDER BY id)), '-') FROM import_batch

UNION ALL SELECT 'txn.count',                    count(*)::text FROM txn
UNION ALL SELECT 'txn.recorded_at.min',          coalesce(min(recorded_at)::text, '-') FROM txn
UNION ALL SELECT 'txn.recorded_at.max',          coalesce(max(recorded_at)::text, '-') FROM txn
UNION ALL SELECT 'txn.txn_date.min',             coalesce(min(txn_date)::text, '-') FROM txn
UNION ALL SELECT 'txn.txn_date.max',             coalesce(max(txn_date)::text, '-') FROM txn
UNION ALL SELECT 'txn.idempotency_key.agg',      coalesce(md5(string_agg(encode(idempotency_key,'hex'), ',' ORDER BY encode(idempotency_key,'hex'))), '-') FROM txn

UNION ALL SELECT 'posting.count',                count(*)::text FROM posting
UNION ALL SELECT 'posting.id.max',               coalesce(max(id)::text, '-') FROM posting
UNION ALL SELECT 'posting.weight_minor.sum',     coalesce(sum(weight_minor)::text, '-') FROM posting
UNION ALL SELECT 'posting.quantity.sum',         coalesce(sum(quantity)::text, '-') FROM posting

UNION ALL SELECT 'position.agg',                 coalesce(md5(string_agg(account||'|'||commodity||'|'||quantity, E'\n' ORDER BY account, commodity)), '-') FROM position
UNION ALL SELECT 'lot.count',                    count(*)::text FROM lot
UNION ALL SELECT 'lot.remaining.sum',            coalesce(sum(remaining_quantity)::text, '-') FROM lot
UNION ALL SELECT 'realized_gain.count',          count(*)::text FROM realized_gain
UNION ALL SELECT 'realized_gain.gain_minor.sum', coalesce(sum(gain_minor)::text, '-') FROM realized_gain

UNION ALL SELECT 'break_record.count',           count(*)::text FROM break_record
UNION ALL SELECT 'break_record.detected_at.min', coalesce(min(detected_at)::text, '-') FROM break_record
UNION ALL SELECT 'break_record.detected_at.max', coalesce(max(detected_at)::text, '-') FROM break_record

UNION ALL SELECT 'reference_data.count',         count(*)::text FROM reference_data
UNION ALL SELECT 'reference_data.fetched_at.max', coalesce(max(fetched_at)::text, '-') FROM reference_data
UNION ALL SELECT 'reference_data_fetch.last_attempt_at.max', coalesce(max(last_attempt_at)::text, '-') FROM reference_data_fetch
UNION ALL SELECT 'reference_data_fetch.last_success_at.max', coalesce(max(last_success_at)::text, '-') FROM reference_data_fetch

-- The schema itself. A restore that dropped a CHECK is a restore that will
-- accept the next bad write, and nothing in the data would say so.
UNION ALL SELECT 'schema.constraints', md5(string_agg(conname||' '||pg_get_constraintdef(oid), E'\n' ORDER BY conname))
  FROM pg_constraint WHERE connamespace = 'public'::regnamespace
UNION ALL SELECT 'schema.constraint_count', count(*)::text
  FROM pg_constraint WHERE connamespace = 'public'::regnamespace
UNION ALL SELECT 'schema.indexes', md5(string_agg(indexdef, E'\n' ORDER BY indexname))
  FROM pg_indexes WHERE schemaname = 'public'
UNION ALL SELECT 'schema.flyway_applied', coalesce(max(version), '-') FROM flyway_schema_history WHERE success;
