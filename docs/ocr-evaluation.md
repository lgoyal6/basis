# OCR evaluation corpus

Run the bounded local evaluation with:

```shell
python3 tools/ocr_eval.py --report /tmp/basis-ocr-evaluation.json
python3 -m unittest discover -s tools -p 'test_ocr_eval.py'
```

The corpus in `src/test/resources/ocr-eval` contains four synthetic, non-sensitive
PNG pages. Each page has one labeled field and a known numeric value. The evaluator
runs the configured Tesseract binary, extracts only fields declared in the manifest,
and reports exact field-pair precision, recall, and per-image failures. Commas are
normalized before comparison; values that do not match remain failures.

This is a repeatable smoke evaluation of the local OCR boundary. It does not measure
scanned filing accuracy, table extraction, handwriting, multilingual text, or
production adoption. OCR output remains untrusted and the application routes OCR
facts to review regardless of this score.
