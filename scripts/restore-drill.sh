#!/usr/bin/env bash
#
# Back basis up, restore it somewhere else, and find out whether what came back
# is a ledger.
#
# Two restores, because they answer different questions and have different
# recoverable data windows:
#
#   logical  pg_dump -Fc from the source, pg_restore into an empty database in
#            a second container. Recovers to the instant the dump began, so the
#            window is the dump interval.
#   pitr     pg_basebackup taken earlier plus the archived WAL, replayed into a
#            third container up to a stated instant. Recovers to any instant
#            covered by the archive, so the window is archive_timeout.
#
# The PITR run also proves it stopped where it was told: rows written after the
# recovery target must be absent, which is the only way to tell a real
# point-in-time recovery from a restore of the latest state.
#
# Needs Docker, a JDK 21 at JAVA_HOME, and build/libs/basis.jar.
#
#   JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./scripts/restore-drill.sh
#
# Everything it makes is named basis-c14-* and torn down at the end unless
# KEEP=1. It never reads BASIS_DB_URL from the environment: the databases here
# are ones this script created seconds earlier.
set -uo pipefail
cd "$(dirname "$0")/.."

JAVA=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21}/bin/java
JAR=build/libs/basis.jar
OUT=${OUT:-build/restore-drill}
TRADES=${TRADES:-6000}
ARCHIVE_TIMEOUT=${ARCHIVE_TIMEOUT:-10}
SRC_PORT=55611
TGT_PORT=55612
PITR_PORT=55613
IMAGE=postgres:16

mkdir -p "$OUT"
say() { printf '\n\033[1;36m== %s\033[0m\n' "$*"; }
load() { uptime | sed 's/.*load averages*: *//'; }
# psql prints a command tag for every statement, so a transcript that contains
# statements carries BEGIN, ALTER TABLE and ROLLBACK that a plain fingerprint
# does not. Every fingerprint key is lower case and every one of those tags is
# upper case, so comparing only the lower-case lines compares the fingerprints
# and nothing else.
keys() { grep -E '^[a-z]' "$1" | sort; }

teardown() {
  [ "${KEEP:-0}" = 1 ] && { echo "left running: basis-c14-src basis-c14-tgt basis-c14-pitr"; return; }
  docker rm -f basis-c14-src basis-c14-tgt basis-c14-pitr >/dev/null 2>&1
  docker volume rm -f basis-c14-archive basis-c14-base >/dev/null 2>&1
}
trap teardown EXIT INT TERM

wait_ready() {  # container
  for _ in $(seq 1 90); do
    docker exec "$1" psql -U basis -d "${2:-basis}" -tAc 'select 1' >/dev/null 2>&1 && return 0
    sleep 1
  done
  echo "$1 never came up"; return 1
}

# ---------------------------------------------------------------- source ----
say "source: postgres with WAL archiving on, archive_timeout=${ARCHIVE_TIMEOUT}s"
docker rm -f basis-c14-src basis-c14-tgt basis-c14-pitr >/dev/null 2>&1
docker volume rm -f basis-c14-archive basis-c14-base >/dev/null 2>&1
docker volume create basis-c14-archive >/dev/null
docker volume create basis-c14-base >/dev/null
docker run -d --name basis-c14-src \
  -e POSTGRES_DB=basis -e POSTGRES_USER=basis -e POSTGRES_PASSWORD=basis \
  -v basis-c14-archive:/archive -v basis-c14-base:/base \
  -p 127.0.0.1:${SRC_PORT}:5432 "$IMAGE" \
  -c wal_level=replica -c archive_mode=on -c archive_timeout="${ARCHIVE_TIMEOUT}" \
  -c "archive_command=test ! -f /archive/%f && cp %p /archive/%f" >/dev/null
wait_ready basis-c14-src || exit 1
docker exec -u root basis-c14-src chown postgres:postgres /archive /base

export BASIS_DB_USER=basis BASIS_DB_PASSWORD=basis
SRC_URL=jdbc:postgresql://localhost:${SRC_PORT}/basis
TGT_URL=jdbc:postgresql://localhost:${TGT_PORT}/basis
PITR_URL=jdbc:postgresql://localhost:${PITR_PORT}/basis

say "seed A: invented statement, ${TRADES} trades, imported by the real importer"
python3 scripts/make-statement.py "$TRADES" 0 > "$OUT/history-a.csv"
python3 scripts/make-statement.py "$TRADES" 1 > "$OUT/history-b.csv"
python3 scripts/make-statement.py "$TRADES" 2 > "$OUT/history-c.csv"
BASIS_DB_URL=$SRC_URL $JAVA -jar $JAR import fidelity "Assets:Broker:Drill" "$OUT/history-a.csv"
BASIS_DB_URL=$SRC_URL $JAVA -jar $JAR cache-split ACME 4:1 --on 2020-08-31
BASIS_DB_URL=$SRC_URL $JAVA -jar $JAR cache-split BOLT 2:1 --on 2021-03-15
printf 'symbol,quantity,cost_basis,kind\nACME,99999,,EQUITY\nBOLT,50,,EQUITY\nCRUX,12000,,EQUITY\n' > "$OUT/positions.csv"
BASIS_DB_URL=$SRC_URL $JAVA -jar $JAR reconcile "Assets:Broker:Drill" "$OUT/positions.csv" --as-of 2026-03-31 >/dev/null

say "base backup, taken here so the WAL after it is what PITR has to replay"
docker exec basis-c14-src bash -c 'rm -rf /base/pristine && pg_basebackup -U basis -D /base/pristine -X stream -c fast' || exit 1

say "seed B: a second import, after the base backup"
BASIS_DB_URL=$SRC_URL $JAVA -jar $JAR import fidelity "Assets:Broker:Drill" "$OUT/history-b.csv"
# The whole state PITR will have to reproduce, captured before the instant that
# names it. Nothing writes between this and the `now()` below, so this is what
# the source looked like at the recovery target - and it is a far stronger
# claim than a row count, because it carries every timestamp and every digest.
docker exec -i basis-c14-src psql -U basis -d basis < scripts/restore-fingerprint.sql \
  | sort > "$OUT/at-target-fingerprint.txt"
RECOVERY_TARGET=$(docker exec basis-c14-src psql -U basis -d basis -tAc 'select now()')
TXNS_AT_TARGET=$(docker exec basis-c14-src psql -U basis -d basis -tAc 'select count(*) from txn')
echo "recovery target $RECOVERY_TARGET, ${TXNS_AT_TARGET} transactions at that instant"

say "seed C: a third import, after the recovery target. PITR must not return it."
docker exec basis-c14-src psql -U basis -d basis -tAc 'select pg_sleep(2)' >/dev/null
BASIS_DB_URL=$SRC_URL $JAVA -jar $JAR import fidelity "Assets:Broker:Drill" "$OUT/history-c.csv"
BASIS_DB_URL=$SRC_URL $JAVA -jar $JAR rebuild | tee "$OUT/src-rebuild.txt"

docker exec -i basis-c14-src psql -U basis -d basis < scripts/restore-invariants.sql > "$OUT/src-invariants.txt"
docker exec -i basis-c14-src psql -U basis -d basis < scripts/restore-fingerprint.sql | sort > "$OUT/src-fingerprint.txt"
BASIS_DB_URL=$SRC_URL $JAVA -jar $JAR status > "$OUT/src-status.txt"

# ------------------------------------------------------- logical restore ----
say "logical: pg_dump -Fc"
echo "load before: $(load)"
DUMP_START=$(date +%s.%N)
docker exec basis-c14-src pg_dump -U basis -d basis -Fc -f /base/basis.dump || exit 1
DUMP_END=$(date +%s.%N)
echo "load after:  $(load)"
DUMP_BYTES=$(docker exec basis-c14-src stat -c %s /base/basis.dump)

say "logical: restore into a second container that has never seen this data"
docker run -d --name basis-c14-tgt \
  -e POSTGRES_DB=basis -e POSTGRES_USER=basis -e POSTGRES_PASSWORD=basis \
  -v basis-c14-base:/base -p 127.0.0.1:${TGT_PORT}:5432 "$IMAGE" >/dev/null
wait_ready basis-c14-tgt || exit 1
# Isolation, asserted rather than assumed.
[ "$SRC_URL" != "$TGT_URL" ] || { echo "target url equals source url"; exit 1; }
EMPTY=$(docker exec basis-c14-tgt psql -U basis -d basis -tAc \
  "select count(*) from information_schema.tables where table_schema='public'")
[ "$EMPTY" = 0 ] || { echo "target was not empty: $EMPTY tables"; exit 1; }
echo "target public schema had $EMPTY tables before the restore"

echo "load before: $(load)"
RESTORE_START=$(date +%s.%N)
docker exec basis-c14-tgt pg_restore -U basis -d basis --exit-on-error /base/basis.dump || exit 1
RESTORE_END=$(date +%s.%N)
echo "load after:  $(load)"

docker exec -i basis-c14-tgt psql -U basis -d basis < scripts/restore-invariants.sql > "$OUT/tgt-invariants.txt"
docker exec -i basis-c14-tgt psql -U basis -d basis < scripts/restore-fingerprint.sql | sort > "$OUT/tgt-fingerprint.txt"
BASIS_DB_URL=$TGT_URL $JAVA -jar $JAR status > "$OUT/tgt-status.txt"
BASIS_DB_URL=$TGT_URL $JAVA -jar $JAR rebuild | tee "$OUT/tgt-rebuild.txt"

# A sentinel written to the source after the restore. If it shows up in the
# target, the two urls were pointing at one database and every number above is
# about a single instance talking to itself.
docker exec basis-c14-src psql -U basis -d basis -c \
  "insert into reference_data(symbol,event_type,event_date,payload,source) values ('SENTINEL','SPLIT','2099-01-01','{}','isolation-probe')" >/dev/null
SENTINEL=$(docker exec basis-c14-tgt psql -U basis -d basis -tAc \
  "select count(*) from reference_data where symbol='SENTINEL'")
echo "sentinel written to source, visible in target: $SENTINEL (must be 0)"
[ "$SENTINEL" = 0 ] || exit 1

# ---------------------------------------------------------- PITR restore ----
say "pitr: replay the archive into a third container, stopping at the target"
docker exec basis-c14-src psql -U basis -d basis -tAc 'select pg_switch_wal()' >/dev/null
docker exec basis-c14-src psql -U basis -d basis -c 'checkpoint' >/dev/null
for _ in $(seq 1 30); do
  PENDING=$(docker exec basis-c14-src psql -U basis -d basis -tAc \
    "select count(*) from pg_stat_archiver where last_failed_wal is not null and (last_archived_wal is null or last_failed_wal > last_archived_wal)")
  [ "$PENDING" = 0 ] && break
  sleep 1
done
docker exec basis-c14-src psql -U basis -d basis -c 'select archived_count, last_archived_wal, last_archived_time, failed_count from pg_stat_archiver'
docker exec basis-c14-src bash -c 'rm -rf /base/pgdata && cp -a /base/pristine /base/pgdata && touch /base/pgdata/recovery.signal && rm -f /base/pgdata/postmaster.pid'

echo "load before: $(load)"
PITR_START=$(date +%s.%N)
docker run -d --name basis-c14-pitr -e PGDATA=/base/pgdata \
  -e POSTGRES_DB=basis -e POSTGRES_USER=basis -e POSTGRES_PASSWORD=basis \
  -v basis-c14-archive:/archive -v basis-c14-base:/base \
  -p 127.0.0.1:${PITR_PORT}:5432 "$IMAGE" \
  -c "restore_command=cp /archive/%f %p" \
  -c "recovery_target_time=$RECOVERY_TARGET" \
  -c recovery_target_action=promote >/dev/null
wait_ready basis-c14-pitr || { docker logs basis-c14-pitr | tail -30; exit 1; }
PITR_END=$(date +%s.%N)
echo "load after:  $(load)"

PITR_TXNS=$(docker exec basis-c14-pitr psql -U basis -d basis -tAc 'select count(*) from txn')
docker exec -i basis-c14-pitr psql -U basis -d basis < scripts/restore-invariants.sql > "$OUT/pitr-invariants.txt"
docker exec -i basis-c14-pitr psql -U basis -d basis < scripts/restore-fingerprint.sql | sort > "$OUT/pitr-fingerprint.txt"
docker logs basis-c14-pitr 2>&1 | grep -iE 'recovery|consistent|promot|redo' | tail -20 > "$OUT/pitr-recovery.log"

# ------------------------------------------------------------- the window ----
# What a crash would cost, sampled rather than quoted. The archive can reach no
# further than its last archived segment, so the recoverable data window at any
# instant is now() minus last_archived_time. Sampled for a minute under a write
# workload, because that is when the number matters and when archive_timeout is
# actually doing something.
say "recoverable data window: sampling for ${WINDOW_SAMPLE_SECS:-60}s under writes"
docker exec basis-c14-src psql -U basis -d basis -c \
  "create table if not exists c14_window_probe(id bigserial primary key, at timestamptz default now())" >/dev/null
WINDOW=0
for _ in $(seq 1 $(( ${WINDOW_SAMPLE_SECS:-60} / 2 ))); do
  docker exec basis-c14-src psql -U basis -d basis -tAc \
    "insert into c14_window_probe default values" >/dev/null
  sample=$(docker exec basis-c14-src psql -U basis -d basis -tAc \
    "select round(extract(epoch from now() - last_archived_time)::numeric, 1) from pg_stat_archiver")
  WINDOW=$(python3 -c "print(max($WINDOW, ${sample:-0}))")
  sleep 2
done
docker exec basis-c14-src psql -U basis -d basis -c "drop table c14_window_probe" >/dev/null

say "results"
python3 - "$DUMP_START" "$DUMP_END" "$RESTORE_START" "$RESTORE_END" "$PITR_START" "$PITR_END" <<'PY'
import sys
d0, d1, r0, r1, p0, p1 = (float(x) for x in sys.argv[1:7])
print(f"dump            {d1-d0:8.2f} s")
print(f"logical restore {r1-r0:8.2f} s")
print(f"pitr recovery   {p1-p0:8.2f} s")
PY
echo "dump size            ${DUMP_BYTES} bytes"
echo "transactions: source $(docker exec basis-c14-src psql -U basis -d basis -tAc 'select count(*) from txn'), target $(docker exec basis-c14-tgt psql -U basis -d basis -tAc 'select count(*) from txn'), pitr ${PITR_TXNS} (target at recovery instant ${TXNS_AT_TARGET})"
echo "recoverable data window: worst observed ${WINDOW} s behind the last archived WAL segment over ${WINDOW_SAMPLE_SECS:-60}s of writes (archive_timeout=${ARCHIVE_TIMEOUT}s)"

say "fingerprint diff, source against logical restore"
diff "$OUT/src-fingerprint.txt" "$OUT/tgt-fingerprint.txt" && echo "identical"
say "invariant diff, source against logical restore"
diff "$OUT/src-invariants.txt" "$OUT/tgt-invariants.txt" && echo "identical"
say "status diff, source against logical restore"
diff "$OUT/src-status.txt" "$OUT/tgt-status.txt" && echo "identical"
say "invariants on the point-in-time copy"
cat "$OUT/pitr-invariants.txt"

# The claim that separates a point-in-time recovery from a restore of the
# latest state: the recovered copy is the source as it stood at the target
# instant, timestamps and digests included, and not as it stood afterwards.
say "point-in-time copy against the source as it stood at the recovery target"
diff <(keys "$OUT/at-target-fingerprint.txt") <(keys "$OUT/pitr-fingerprint.txt") \
  && echo "identical: the replay stopped where it was told"
say "and it is NOT the source as it stands now (seed C must be absent)"
diff <(keys "$OUT/src-fingerprint.txt") <(keys "$OUT/pitr-fingerprint.txt") >/dev/null \
  && echo "IDENTICAL -- the replay did not stop at the target" \
  || echo "differs, as it must: the third import is not in the recovered copy"

# ------------------------------------------------------ negative controls ----
# Every check above passed. A check that cannot fail is not a check, so each
# one is now broken on purpose against the restored copy and has to say so.
#
# The SQL controls run inside a transaction that is rolled back, in one psql
# session, so the tamper and the check see each other and nothing survives.
# PostgreSQL rolls DDL back too, which is what lets the dropped-CHECK control
# be undone.
say "negative controls: break the restored copy on purpose"
NCDIR="$OUT/nc"; mkdir -p "$NCDIR"

control() {  # name, file, tamper-sql, check-sql-file
  local name=$1 file=$2 tamper=$3 checks=$4
  { echo "BEGIN;"; echo "$tamper"; cat "$checks"; echo "ROLLBACK;"; } \
    | docker exec -i basis-c14-tgt psql -U basis -d basis > "$NCDIR/$file" 2>&1
  echo "--- $name"
  grep -q '^ERROR:' "$NCDIR/$file" && {
    echo "  THE CONTROL ITSELF ERRORED, so it proves nothing:"
    grep '^ERROR:' "$NCDIR/$file" | sed 's/^/    /'
  }
}

# NC1. One posting lost in transit. The ledger's central rule is the first
# thing that has to notice.
control "nc1 a lost posting" nc1-invariants.txt \
  "DELETE FROM posting WHERE id = (SELECT max(id) FROM posting);" \
  scripts/restore-invariants.sql
grep -E 'FAIL' "$NCDIR/nc1-invariants.txt" || echo "  NO FAIL -- the control did not control anything"

# NC2. A lot that no longer matches the postings that opened and closed it.
# Stays inside the CHECK, so only the conservation invariant can see it.
control "nc2 a lot that stopped matching its postings" nc2-invariants.txt \
  "UPDATE lot SET remaining_quantity = remaining_quantity - 1
     WHERE lot_id = (SELECT lot_id FROM lot WHERE remaining_quantity > 0 ORDER BY lot_id LIMIT 1);" \
  scripts/restore-invariants.sql
grep -E 'FAIL' "$NCDIR/nc2-invariants.txt" || echo "  NO FAIL -- the control did not control anything"

# NC3. A CHECK constraint the restore did not recreate. No row changes, so
# only the schema lines of the fingerprint can see it. This is the control for
# "a restore that brought every row and dropped every constraint".
control "nc3 a dropped CHECK constraint" nc3-fingerprint.txt \
  "ALTER TABLE lot DROP CONSTRAINT lot_remaining_within_original;" \
  scripts/restore-fingerprint.sql
keys "$NCDIR/nc3-fingerprint.txt" > "$NCDIR/nc3-sorted.txt"
diff <(keys "$OUT/tgt-fingerprint.txt") "$NCDIR/nc3-sorted.txt" > "$NCDIR/nc3-diff.txt" \
  && echo "  NO DIFF -- the control did not control anything" \
  || { echo "  fingerprint noticed:"; sed 's/^/    /' "$NCDIR/nc3-diff.txt"; }

# NC4. A timestamp re-defaulted to now(), which is what a restore that
# recreated rows instead of restoring them would leave behind. Counts and
# digests are untouched; only the instants move.
control "nc4 a re-defaulted timestamp" nc4-fingerprint.txt \
  "UPDATE txn SET recorded_at = now()
     WHERE id = (SELECT id FROM txn ORDER BY recorded_at DESC, id LIMIT 1);" \
  scripts/restore-fingerprint.sql
keys "$NCDIR/nc4-fingerprint.txt" > "$NCDIR/nc4-sorted.txt"
diff <(keys "$OUT/tgt-fingerprint.txt") "$NCDIR/nc4-sorted.txt" > "$NCDIR/nc4-diff.txt" \
  && echo "  NO DIFF -- the control did not control anything" \
  || { echo "  fingerprint noticed:"; sed 's/^/    /' "$NCDIR/nc4-diff.txt"; }

# NC5. The referential checks, shown not to be vacuous, which is exactly the
# state a data-only restore into a schema whose foreign keys were never created
# produces.
#
# The parents' keys are rewritten rather than deleted. Deleting txn does orphan
# posting and realized_gain, but it also empties the table that
# `ref txn to import_batch` reads, so that check passes on nothing and looks
# fine. Rewriting leaves every row in place and pointing nowhere, so all three
# checks have rows to fail on.
control "nc5 orphans the foreign keys would have refused" nc5-invariants.txt \
  "SET session_replication_role = replica;
   UPDATE import_batch SET id = id + 1000000;
   UPDATE txn SET id = gen_random_uuid();" \
  scripts/restore-invariants.sql
grep -E 'FAIL' "$NCDIR/nc5-invariants.txt" || echo "  NO FAIL -- the control did not control anything"

# The restored copy is unchanged: every control above was rolled back.
docker exec -i basis-c14-tgt psql -U basis -d basis < scripts/restore-fingerprint.sql \
  | sort > "$NCDIR/after-controls.txt"
say "the restored copy after the controls"
diff <(keys "$OUT/tgt-fingerprint.txt") <(keys "$NCDIR/after-controls.txt") \
  && echo "unchanged: every control was rolled back"
