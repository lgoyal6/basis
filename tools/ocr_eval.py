#!/usr/bin/env python3
"""Run a small, reproducible OCR field-level evaluation.

The corpus is synthetic and intentionally small. It measures the configured OCR
binary on known labels; it is not a claim about scanned filings or production
document accuracy.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any


def load_manifest(path: Path) -> dict[str, Any]:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("schema_version") != 1:
        raise ValueError("unsupported corpus schema")
    cases = manifest.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError("corpus must contain at least one case")
    return manifest


def extract_fields(text: str, field_names: set[str]) -> list[tuple[str, str]]:
    """Extract configured `FIELD value` pairs from provider text.

    The evaluator deliberately only scores fields declared by the corpus. Text
    outside those fields remains a failure detail rather than becoming a fact.
    """
    if not field_names:
        return []
    alternatives = "|".join(sorted((re.escape(name) for name in field_names), key=len, reverse=True))
    pattern = re.compile(rf"(?im)^\s*({alternatives})\s*[:=]?\s*([0-9][0-9,]*)\b")
    return [(match.group(1).upper(), match.group(2).replace(",", "")) for match in pattern.finditer(text)]


def run_ocr(binary: str, image: Path) -> str:
    # Running from the image directory keeps this compatible with the macOS
    # Homebrew tesseract build, which mishandles some absolute input paths.
    result = subprocess.run(
        [binary, image.name, "stdout", "--psm", "6"],
        cwd=image.parent,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode:
        raise RuntimeError(f"OCR failed for {image.name} with exit code {result.returncode}")
    return result.stdout


def evaluate(corpus: Path, binary: str) -> dict[str, Any]:
    manifest = load_manifest(corpus / "manifest.json")
    field_names = {item["field"].upper() for case in manifest["cases"] for item in case["expected"]}
    expected_pairs: list[tuple[str, str]] = []
    predicted_pairs: list[tuple[str, str]] = []
    failures: list[dict[str, Any]] = []
    cases: list[dict[str, Any]] = []
    for case in manifest["cases"]:
        image = corpus / case["image"]
        if not image.is_file():
            raise FileNotFoundError(image)
        expected = [(item["field"].upper(), str(item["value"]).replace(",", "")) for item in case["expected"]]
        observed_text = run_ocr(binary, image)
        observed = extract_fields(observed_text, field_names)
        expected_pairs.extend(expected)
        predicted_pairs.extend(observed)
        matched = sum(pair in observed for pair in expected)
        case_result = {
            "image": image.name,
            "expected": [{"field": f, "value": v} for f, v in expected],
            "observed": [{"field": f, "value": v} for f, v in observed],
            "matched": matched,
            "raw_output": observed_text.strip(),
        }
        cases.append(case_result)
        if matched != len(expected) or len(observed) != matched:
            failures.append(case_result)

    true_positive = sum(pair in predicted_pairs for pair in expected_pairs)
    precision = true_positive / len(predicted_pairs) if predicted_pairs else 0.0
    recall = true_positive / len(expected_pairs) if expected_pairs else 0.0
    return {
        "schema_version": 1,
        "corpus": manifest["description"],
        "tool": binary,
        "cases": len(cases),
        "expected_fields": len(expected_pairs),
        "predicted_fields": len(predicted_pairs),
        "true_positive_fields": true_positive,
        "field_precision": round(precision, 3),
        "field_recall": round(recall, 3),
        "failure_cases": failures,
        "case_results": cases,
        "limitations": [
            "Synthetic rendered labels are not representative of scanned filings.",
            "The corpus has four fields and does not measure tables, handwriting, layout, or multilingual text.",
            "OCR output remains untrusted evidence and is routed to human review by the application.",
        ],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--corpus", type=Path, default=Path("src/test/resources/ocr-eval"))
    parser.add_argument("--binary", default=shutil.which("tesseract") or "tesseract")
    parser.add_argument("--report", type=Path, help="also write the JSON report to this path")
    args = parser.parse_args()
    try:
        report = evaluate(args.corpus.resolve(), args.binary)
    except (OSError, ValueError, RuntimeError, KeyError) as error:
        print(f"ocr evaluation failed: {error}", file=sys.stderr)
        return 2
    encoded = json.dumps(report, indent=2, sort_keys=True) + "\n"
    print(encoded, end="")
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(encoded, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
