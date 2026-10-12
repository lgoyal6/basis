# 0012: Durable extraction pipeline wiring

Status: accepted, 2026-10-12

The durable worker now owns orchestration, while `DocumentPipelineService` owns the
document-specific stage adapters. A claimed job loads its immutable content-addressed
object, selects the registered extractor, persists append-only extracted fields, creates
normalized facts only when numeric and period evidence is available, and routes OCR,
low-confidence, or incomplete values to review.

Stage artifacts are JSON and committed with the processing checkpoint and outbox event.
Field IDs are deterministic per job and ordinal, so a lease expiry or process restart
cannot duplicate extracted rows. A later implementation can add table parsing and
cross-document reconciliation without changing the worker contract.

This milestone does not claim model-assisted extraction, production object storage,
or cross-document matching. Values without a safe period or numeric parse remain in
the immutable extraction layer and are reported as incomplete rather than coerced.
