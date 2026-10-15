# Local document study and source freshness

The Java workbench retains immutable source versions. New-byte ingest advances
`document.current_version_id` while holding the tenant row lock. Review takes the
same lock before the task/fact locks and rejects a known stale source. Retries of
old bytes return the original receipt without moving the current pointer back.
The migration selects the most recently created existing version, breaking any
historical timestamp ties by UUID. That historical tie policy cannot recover an
unrecorded ingest order and needs operator review for tied imported versions.

Review decisions persist `result_fact_id`, so a correction binds its exact new
fact rather than every successor of the old fact. V16 backfills historical
corrections only from stored atomic command responses; absent evidence is excluded.
Exports run one SQL statement so approved facts, their approving/correcting
review decisions, and source freshness come from one MVCC snapshot. The CSV adds
`review_decision_id` as its last column. Consumers that require an exact header
must accept this additive column before deployment. Superseded, unapproved,
reviewless APPROVED, and known stale source facts are excluded. An export that
starts before a concurrent update may reflect the earlier committed snapshot.
Standalone historical facts without a registered document version retain their
prior source fields and require a persisted approval decision; they cannot
establish current-source validation. Workbench tenant authentication remains the
existing configured bearer-token mechanism. These checks do not establish RLS,
OIDC, encrypted local storage, hosted identity or a live cloud policy.

## Reproducible local diagnostics

Use a private Python environment with `tools/requirements-local-tests.txt` for
the offline tests, or `tools/requirements-source-finding.txt` for the pinned
encoder comparison. CI runs the local tests without downloading model weights.
Corpus bytes, frozen protocols and output receipts belong outside this repository.

1. `python tools/document_study.py generate <private-corpus-directory>` creates
   original controlled development fixtures, four families and 40 documents.
   Labels are programmatic, not independently adjudicated. Train/dev/test have
   disjoint document, issuer and template identities. The test split has 100 pages.
2. `python tools/document_study.py freeze <manifest.json> <protocol.json>` locks
   image/manifest/code hashes, split, seed, OCR configuration and metric definitions.
   Protocol creation refuses overwrite. Preserve an old protocol/output if a
   failure requires a new revision, and explain the change before rerunning.
3. Run `python tools/document_study.py run <manifest.json> <protocol.json> <new-output-directory>`
   through the portfolio execution root's `scripts/run_heavy.py` measurement lock.
   It saves raw OCR text, exact fact observations, family results, document-cluster
   recall intervals and wrong-page/unit/date/version/duplicate/missing controls.
   The historical global field/value scorer is retained as the failure baseline.
4. Download only the pinned Apache-2.0 MiniLM encoder declared by
   `tools/retrieval_study.py`, retaining its model card, revision and file hashes.
   `python tools/retrieval_study.py freeze <manifest> <ocr-cases> <local-model-directory> <protocol>`
   freezes retrieval inputs before comparisons. The corresponding `run` command
   accepts an additional new output directory and also needs the measurement lock.
   It compares page and token chunks, BM25, dense cosine and reciprocal-rank fusion
   under the same encoder-token context budget. All axes, including losing ones,
   stay in the results. LangChain uses its actual Runnable and InMemoryVectorStore
   APIs. No pgvector, hosted vector database, reranker or generated answer is implied.

`SourceIndex` filters tenant and current versions before chunking, corpus statistics
and ranking. Deletion and revocation apply to subsequent queries immediately.
Documents remain inert source text. This is a local source-finding library/study,
not an application-integrated semantic search endpoint or a customer deployment.

The parser only accepts explicit known financial labels with numeric values,
units and dates. Missing or corrupt OCR fields remain review failures. All OCR
facts require review; the study does not simulate approvals or human time.
The independent 100-page/500-field public/redacted study is still required,
as are consented counterbalanced reviewer sessions. `tools/review_study.py` can
validate and summarize externally collected sessions with bound consent/raw
receipts, active intervals, task completion, corrections and unsafe exports.
Receipt existence alone cannot authenticate consent or independence. Its unit
test records are test data only and never enter study summaries.

Run `python -m unittest discover -s tools -p 'test_*.py' -v` and the Gradle document
tests for local checks. Provider SDK mocks, local PostgreSQL, synthetic studies,
live cloud staging and independent human review are separate evidence stages.

## Operations and rollback

Apply Flyway V15 and V16 before using the new ingest/review/export code. Retain originals,
versions, fact and review-decision rows together according to the tenant retention
policy. No retention deletion job is introduced here. Startup/health and durable
worker retry/cancellation remain existing workbench operations. Database tests
exercise rollback on outbox failure and resume at every stage, rather than a
production restart or deployment drill. Correlation IDs remain the job ID.
For an application rollback, preserve V15/V16 and their data, and gate exports until
the previous exporter is replaced or independently checked: old code lacks the
new current-source and decision filters. No rollback or live deployment was run.

## Redacted trace seam

`tools/source_trace.py` defines a versioned Pydantic receipt with run/attempt,
selected model and tool identity, tenant/query/source hashes, and bounded outcome
counts. `export_langsmith` accepts an explicitly supplied client; it never creates
one or enables tracing on its own. Its test executes the actual LangSmith SDK
against an in-process offline HTTP transport and verifies that sensitive source,
query and tenant strings never enter the body. This is an SDK serialization
contract, not a hosted trace or dataset readback. Hosted LangSmith access remains
blocked until an authorized workspace, credential and permitted ingestion exist.


## Verified product-path boundary

The Java OCR adapter retains every raw line. It additionally emits a low-confidence
numeric candidate only for an allowlisted financial label followed by a numeric
amount, an explicit supported currency/share unit, and a valid ISO date. Invalid
dates, missing amounts/units/dates and free-form prose stay raw evidence. Both raw
and candidate locations retain page, line and immutable source version. This is a
conservative structured-line parser, not a general table or filing extractor.

Direct image inputs must have a format whose headers ImageIO can read, at most
20 frames, at most 10,000 pixels on either side of every frame, and at most
20 million pixels across all frames. Header checks run before launching OCR and
do not decode the raster. Unsupported or corrupt headers are refused. PDF page
limits and the existing 2,400-pixel rasterization bound remain unchanged.
These admission limits reduce image expansion risk; they do not prove a hard
memory bound for the external OCR process.

`DocumentHttpWorkflowTest` starts the real embedded HTTP server on an ephemeral
local port and disposable PostgreSQL, uploads a generated PNG, invokes installed
Tesseract through the durable pipeline, checks review-required state, approves
through the authenticated API, and exports the decision-bound fact. It checks
that another tenant receives 401 and that a new source removes the earlier fact
from current exports. The test actively caught a CSV-route error-content-type
bug, whose fix returns a JSON 401. This is a local integration test with a test
review action, not a human session, browser task, deployment or adoption receipt.

The container build pins the observed Java/Gradle base image digests and installs
explicit Tesseract and Poppler package versions before switching to the existing
non-root `basis` user. Both tools were absent from the original JRE image. These
process dependencies provide OCR and scanned-PDF rasterization; they do not add
remote OCR calls. OS transitive packages still come from the selected Ubuntu
repositories, so retain the built image digest and package inventory for exact
reproduction. No image publication or remote rollout is authorized by this test.

## Quality review and frozen study sources

The initial OCR/retrieval receipts remain bound to their original code hashes.
The execution run retains that source tree and jar before later quality edits.
Reproduce those original measurements from the retained source snapshot, not by
replacing its code with the current scorer. New runs need a new frozen protocol
and output directory; an old holdout result is never relabeled as a new result.

Current validation rejects boolean page numbers and malformed comma grouping.
`SourceIndex` retains one visible corpus to avoid encoding unchanged document
chunks for each query. A tenant/version/chunk-policy change replaces it; deletion
and revocation invalidate it. Each query holds its own store/metadata tuple so
concurrent scopes cannot exchange results. Tests count encoding calls and check
invalidation/concurrent scopes. No new latency or throughput result is claimed.
The original six-axis comparison rebuilt indexes and its timings remain confounded.

Remote adapter reads/uploads are capped at 50 MiB, but SDK buffers can hold more
than one copy. The local study uses two encoder CPU threads. Existing stage IDs
make retries idempotent; private stage annotations do not provide one transaction
across every SQL write. Checkpoint/outbox rollback tests and stage restart tests
are the verified boundary, rather than an OS-kill test at every instruction.
