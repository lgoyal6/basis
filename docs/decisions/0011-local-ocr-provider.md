# 0011: Optional local OCR provider

Scanned pages need an OCR boundary, but the document workflow must not claim a
scanned value is as reliable as selectable PDF text. Basis therefore ships an
optional Tesseract adapter. It is enabled only when both `tesseract` and
`pdftoppm` are available, invokes them with argument arrays, and stores OCR
fields with lower confidence plus page and line provenance. A missing toolchain
leaves the adapter unavailable instead of accepting work that cannot run.

The adapter does not approve or normalize financial facts. Review and
provenance validation remain responsible for deciding whether an OCR value can
enter reconciliation.
