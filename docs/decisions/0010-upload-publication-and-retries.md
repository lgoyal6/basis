# 0010. Upload publication and retry identity

Status: implemented locally, 2026-10-08

Multipart and byte-array ingestion both enter a Spring transaction. A tenant row lock
serializes uploads for that tenant, avoiding conflicting retry keys and duplicate jobs.
Retry identity includes the document key, filename, media type and content hash. A key
reused for different input returns a conflict. New keys for identical content alias the
original job, preserving the original document identity instead of processing it again.
Explicit reprocessing is a separate worker retry operation.

Local objects are hash-verified, fsynced, and published with an atomic hard link that
cannot replace an existing name. Incomplete temporary files are removed on failure.
Reads reject traversal, symlinks and corrupt content. The root must be private to the
service and on a filesystem supporting hard links and directory fsync. Database rollback
can leave a valid content-addressed object, which a retry reuses; it cannot expose a
partial document or create a successful job without its outbox record.

Current upload admission is CSV only, matching the implemented extractor. PDF, OCR,
XLSX and structured filing admission must be enabled with tested extraction adapters.
The earlier storage ADR describes the target architecture, not current implementation:
this adapter does not yet encrypt blobs, sign URLs, implement tenant RLS or provision
per-role tokens. Workbench authentication currently uses configured credentials.
