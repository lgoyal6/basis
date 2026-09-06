-- What a restored basis database has to be able to say about itself.
--
-- Row counts are not on this list. A restore that copied every row and dropped
-- every CHECK would pass a count and still be a ledger nobody should trust, so
-- everything here is either a constraint the database is still enforcing, a
-- relationship between two tables, or one of the ledger's own invariants
-- restated in SQL. Each check prints its name, the number of rows that break
-- it, and PASS or FAIL, so a failure names itself rather than needing a diff.
--
-- Run against source and target and compare, or run against the target alone:
--   psql -f scripts/restore-invariants.sql
\pset footer off
\pset border 2

WITH checks AS (

  -- Invariant 1. Postings sum to zero per transaction, per currency. The
  -- ledger's central rule, checkable by anyone with a psql prompt because
  -- weight_minor is stored rather than recomputed on read.
  SELECT 'inv1 transactions balance at cost' AS name, count(*) AS offending FROM (
    SELECT txn_id, weight_currency FROM posting
    GROUP BY 1, 2 HAVING sum(weight_minor) <> 0) x

  -- Invariant 2. Lot conservation, against the postings rather than against
  -- the lot row's own two columns: acquired is the sum of the positive
  -- postings carrying the lot, remaining is the sum of all of them.
  UNION ALL
  SELECT 'inv2 lots conserved against postings', count(*) FROM (
    SELECT l.lot_id
    FROM lot l LEFT JOIN posting p ON p.lot_id = l.lot_id
    GROUP BY l.lot_id, l.original_quantity, l.remaining_quantity
    HAVING coalesce(sum(p.quantity) FILTER (WHERE p.quantity > 0), 0) <> l.original_quantity
        OR coalesce(sum(p.quantity), 0) <> l.remaining_quantity) x

  -- Invariant 3. Every security position equals the sum of its open lots.
  UNION ALL
  SELECT 'inv3 positions equal open lots', count(*) FROM (
    SELECT p.account FROM position p
    WHERE EXISTS (SELECT 1 FROM posting po
                  WHERE po.account = p.account AND po.commodity = p.commodity
                    AND po.commodity_class <> 'CURRENCY')
      AND p.quantity <> coalesce((SELECT sum(l.remaining_quantity) FROM lot l
                                  WHERE l.account = p.account AND l.commodity = p.commodity), 0)) x

  -- Invariant 5. Proceeds identity, in whole minor units.
  UNION ALL
  SELECT 'inv5 gain equals proceeds minus basis', count(*)
  FROM realized_gain WHERE gain_minor <> proceeds_minor - basis_minor

  -- Invariant 6. Every position, cash included, is the sum of its postings.
  -- This is the one that notices if cash stopped moving when securities did.
  UNION ALL
  SELECT 'inv6 positions equal summed postings', count(*) FROM (
    SELECT p.account, p.commodity FROM position p
    WHERE p.quantity <> coalesce((SELECT sum(po.quantity) FROM posting po
                                  WHERE po.account = p.account AND po.commodity = p.commodity), 0)
    UNION
    SELECT po.account, po.commodity FROM posting po
    GROUP BY po.account, po.commodity
    HAVING sum(po.quantity) <> coalesce((SELECT p.quantity FROM position p
                                         WHERE p.account = po.account AND p.commodity = po.commodity), 0)) x

  -- Referential integrity, asserted rather than assumed. A data-only restore
  -- into a schema whose foreign keys were never created leaves orphans that
  -- no constraint would have caught.
  UNION ALL
  SELECT 'ref txn to import_batch', count(*)
  FROM txn t WHERE NOT EXISTS (SELECT 1 FROM import_batch b WHERE b.id = t.import_batch_id)
  UNION ALL
  SELECT 'ref posting to txn', count(*)
  FROM posting p WHERE NOT EXISTS (SELECT 1 FROM txn t WHERE t.id = p.txn_id)
  UNION ALL
  SELECT 'ref realized_gain to txn', count(*)
  FROM realized_gain g WHERE NOT EXISTS (SELECT 1 FROM txn t WHERE t.id = g.txn_id)

  -- Uniqueness the ledger relies on for idempotent re-import and for ordering.
  UNION ALL
  SELECT 'uniq txn idempotency_key', count(*) FROM (
    SELECT idempotency_key FROM txn GROUP BY 1 HAVING count(*) > 1) x
  UNION ALL
  SELECT 'uniq posting ordinal per txn', count(*) FROM (
    SELECT txn_id, ordinal FROM posting GROUP BY 1, 2 HAVING count(*) > 1) x

  -- The CHECK constraints, restated. If the restore recreated them these
  -- cannot fail; if it did not, these are the only thing looking.
  UNION ALL
  SELECT 'chk batch not both committed and abandoned', count(*)
  FROM import_batch WHERE committed_at IS NOT NULL AND abandoned_at IS NOT NULL
  UNION ALL
  SELECT 'chk cost annotation all or nothing', count(*) FROM posting
  WHERE NOT ((cost_unit_amount IS NULL AND cost_currency IS NULL AND cost_date IS NULL AND lot_id IS NULL)
          OR (cost_unit_amount IS NOT NULL AND cost_currency IS NOT NULL AND cost_date IS NOT NULL AND lot_id IS NOT NULL))
  UNION ALL
  SELECT 'chk cash carries no lot, security carries one', count(*) FROM posting
  WHERE (commodity_class = 'CURRENCY') <> (lot_id IS NULL)
  UNION ALL
  SELECT 'chk lot remaining within original', count(*) FROM lot
  WHERE remaining_quantity < 0 OR remaining_quantity > original_quantity
  UNION ALL
  SELECT 'chk break resolved state matches timestamp', count(*) FROM break_record
  WHERE (status = 'OPEN') <> (resolved_at IS NULL)

  -- No batch left in flight. A restore taken mid import would land here, and
  -- silence about it is how a half written batch becomes permanent.
  UNION ALL
  SELECT 'no import batch left in flight', count(*)
  FROM import_batch WHERE committed_at IS NULL AND abandoned_at IS NULL
)
SELECT name, offending, CASE WHEN offending = 0 THEN 'PASS' ELSE 'FAIL' END AS verdict
FROM checks ORDER BY name;
