#!/usr/bin/env bash
#
# One command that shows what basis is for, on invented data, with no API key.
#
# It builds a history that never applied a split, asks the broker's own position
# snapshot whether that history is right, and lets basis explain the difference.
#
#   ./scripts/demo.sh
#
# Needs JDK 21 and Docker. Leaves nothing behind: the database is torn down at the end
# unless you pass --keep.

set -euo pipefail
cd "$(dirname "$0")/.."

KEEP=false
[ "${1:-}" = "--keep" ] && KEEP=true

ACCOUNT="Assets:Broker:Demo"
# Under build/ so the paths printed below stay short and readable.
WORK="build/demo"
mkdir -p "$WORK"
cleanup() {
  rm -rf "$WORK"
  if [ "$KEEP" = false ]; then
    docker compose down -v >/dev/null 2>&1 || true
  else
    echo "database left running. tear it down with: docker compose down -v"
  fi
}
trap cleanup EXIT

say() { printf '\n\033[1;36m%s\033[0m\n' "$1"; }

# Find a JDK 21 and pin it, rather than trusting whatever `java` happens to be first
# on PATH. The common way to satisfy "needs JDK 21" on a Mac is `brew install
# openjdk@21`, and Homebrew deliberately does not link that into
# /Library/Java/JavaVirtualMachines, so Gradle's toolchain detection cannot see it and
# fails with a message that never mentions Homebrew. Better to say so here.
java_major() { "$1/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1; }
if [ -z "${JAVA_HOME:-}" ] || [ "$(java_major "$JAVA_HOME")" != "21" ]; then
  for candidate in \
      "$(/usr/libexec/java_home -v 21 2>/dev/null || true)" \
      /opt/homebrew/opt/openjdk@21 \
      /usr/local/opt/openjdk@21 \
      /usr/lib/jvm/java-21-openjdk-amd64 \
      /usr/lib/jvm/java-21-openjdk-arm64; do
    if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ] && [ "$(java_major "$candidate")" = "21" ]; then
      JAVA_HOME="$candidate"
      break
    fi
  done
fi
if [ -z "${JAVA_HOME:-}" ] || [ "$(java_major "$JAVA_HOME")" != "21" ]; then
  cat >&2 <<'MSG'
No JDK 21 found. basis targets 21 and Gradle will not fall back to an older one.

  macOS:   brew install openjdk@21
           export JAVA_HOME=/opt/homebrew/opt/openjdk@21
           (Homebrew keeps openjdk@21 unlinked, so JAVA_HOME is how anything finds it)
  Debian:  sudo apt install openjdk-21-jdk
           export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-$(dpkg --print-architecture)
MSG
  exit 1
fi
export JAVA_HOME
JAVA="$JAVA_HOME/bin/java"
echo "using JDK 21 at $JAVA_HOME"

run() { printf '\033[1;32m$ basis %s\033[0m\n' "$*"; "$JAVA" -jar build/libs/basis.jar "$@"; }

say "Building"
./gradlew bootJar --console=plain -q

say "Starting Postgres 16"
docker compose up -d >/dev/null
# By container id, not by the name "basis-db-1": Compose names containers after the
# project, which is the checkout directory. Cloned into anything but ./basis, a
# hardcoded name matches nothing and this loop waits forever.
DB_CONTAINER="$(docker compose ps -q db)"
until [ "$(docker inspect -f '{{.State.Health.Status}}' "$DB_CONTAINER" 2>/dev/null)" = "healthy" ]; do
  sleep 1
done

export BASIS_DB_URL=jdbc:postgresql://localhost:5432/basis
export BASIS_DB_USER=basis BASIS_DB_PASSWORD=basis

# A statement in Fidelity's real column layout. Two rows: money arrives, shares are
# bought. Nothing here mentions a split, which is the point: statements usually do not.
cat > "$WORK/history.csv" <<'CSV'
Run Date,Account,Account Number,Action,Symbol,Description,Type,Price ($),Quantity,Commission ($),Fees ($),Accrued Interest ($),Amount ($),Settlement Date
01/02/2020,Individual,DEMO,ELECTRONIC FUNDS TRANSFER RECEIVED (Cash),,No Description,Cash,,,,,,5000,01/02/2020
01/03/2020,Individual,DEMO,YOU BOUGHT DEMO CORP (DEMO) (Cash),DEMO,DEMO CORP,Cash,300.00,10,,,,-3000,01/06/2020
CSV

# What the broker says you hold today. Forty shares, because DEMO split 4 for 1 and
# the statements above never said so.
printf 'symbol,quantity,cost_basis,kind\nDEMO,40,,EQUITY\n' > "$WORK/positions.csv"

say "1. Import the statement"
run import fidelity "$ACCOUNT" "$WORK/history.csv"

say "2. Ask the broker's snapshot whether the ledger is right"
echo "   (the broker says 40 shares. basis computed 10.)"
run reconcile "$ACCOUNT" "$WORK/positions.csv" --as-of 2026-03-31 || true

say "   Note the ratio was found, but it is only arithmetic. basis says so and stops."

say "3. Tell basis about the split, as if from a broker notice"
echo "   (refresh-splits would fetch this from a provider. cache-split needs no key.)"
run cache-split DEMO 4:1 --on 2020-08-31

say "4. Reconcile again. Same numbers, but now there is evidence"
run reconcile "$ACCOUNT" "$WORK/positions.csv" --as-of 2026-03-31 || true

say "5. Do what it said, using the id the output just gave us"
# Read back rather than hardcoded: the id is whatever the database assigned, and the
# whole point of printing it is that you do not have to know it in advance.
# "|| true" because breaks exits 3 when it finds any, which is by design and not a
# failure. Under "set -e" that would end the script here, which is exactly the trap this
# exit code sets for anyone scripting against it.
BREAK_ID="$( { "$JAVA" -jar build/libs/basis.jar breaks "$ACCOUNT" || true; } \
  | sed -n 's/.*basis apply break \([0-9]*\).*/\1/p' | head -1)"
if [ -z "$BREAK_ID" ]; then
  echo "no confirmed break to apply; stopping so this does not look like it worked"
  exit 1
fi
run apply break "$BREAK_ID"

say "6. Confirm nothing is left"
run reconcile "$ACCOUNT" "$WORK/positions.csv" --as-of 2026-03-31

say "7. Invariant 7: throw the derived state away and replay it from the postings"
run rebuild

say "Done. The ledger and the broker now agree, and every step is in the posting table."
