#!/bin/bash
# Run Schemathesis against the checked contract and a real, isolated basis.
#
# The tests under src/test drive the app through MockMvc, which assembles a well formed
# request for you: it can vary what is inside a multipart part and never the framing
# around it. This drives a socket, so a body whose closing boundary is missing, a field
# sent twice and a POST with no body at all are all reachable. All four defects in
# ApiSchemathesisRegressionTest lived in exactly those shapes.
#
# The database is a throwaway container, and no uploaded statement goes near it anyway:
# SessionStore holds uploads in memory and nothing else. The generated corpus includes
# POST /delete and POST /check, so pointing this at a deploy someone is using would be
# wrong regardless.
#
#     scripts/schemathesis.sh            # the standard run
#     PORT=8899 EXAMPLES=1000 scripts/schemathesis.sh
#
# Four reports are expected and are not defects.
#
# Three are "undocumented status 200" on GET /demo, POST /delete and POST /resolve. Those
# routes answer 302 with a Location, which is what the contract says and what curl -i
# shows; Schemathesis follows the redirect and attributes the final page's 200 to the
# operation that started the chain. --max-redirects 0 does not help: requests raises
# rather than returning the 302, which turns three reports into four errors.
#
# The fourth is "API rejected schema-compliant request" on POST /check for a zero-byte
# history part. The schema says minLength 1 and the tool does not apply minLength to a
# `format: binary` string, so it generates the empty file anyway. basis is right to
# refuse it; OpenAPI cannot say "a file with a readable statement in it".
set -euo pipefail

PORT="${PORT:-8733}"
EXAMPLES="${EXAMPLES:-300}"
SEED="${SEED:-20260905}"
DB_PORT="${DB_PORT:-55442}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONTAINER="basis-schemathesis-$$"

cleanup() {
    kill "${SERVER:-}" 2>/dev/null || true
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run -d --name "$CONTAINER" -e POSTGRES_USER=basis -e POSTGRES_PASSWORD=basis \
    -e POSTGRES_DB=basis -p "$DB_PORT":5432 postgres:16-alpine >/dev/null
for _ in $(seq 1 60); do
    docker exec "$CONTAINER" pg_isready -U basis >/dev/null 2>&1 && break
    sleep 0.5
done

"$ROOT/gradlew" -p "$ROOT" bootJar -q
BASIS_DB_URL="jdbc:postgresql://localhost:$DB_PORT/basis" BASIS_DB_USER=basis \
    BASIS_DB_PASSWORD=basis java -jar "$ROOT/build/libs/basis.jar" serve \
    --server.port="$PORT" &
SERVER=$!

for _ in $(seq 1 90); do
    curl -sf "http://127.0.0.1:$PORT/health" >/dev/null 2>&1 && break
    sleep 1
done

# uvx keeps the tool out of the build: Schemathesis is a Python client and has no business
# in a Gradle dependency block. Pinning the version is what makes two runs comparable.
uvx schemathesis@4.25.2 run "$ROOT/docs/openapi.json" \
    --url "http://127.0.0.1:$PORT" \
    --checks all \
    --phases examples,coverage,fuzzing,stateful \
    -n "$EXAMPLES" \
    --seed "$SEED" \
    --continue-on-failure \
    "$@"
