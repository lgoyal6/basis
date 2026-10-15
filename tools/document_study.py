#!/usr/bin/env python3
"""Freeze and run a controlled local OCR diagnostic.
Uses ocr_eval for Tesseract and document_eval for provenance scoring.
Generated labels remain separate from independent document and reviewer studies.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import platform
import random
import re
import shutil
import subprocess
import time
from collections import defaultdict
from decimal import Decimal
from pathlib import Path

from document_eval import evaluate
from ocr_eval import run_ocr

SCHEMA = ["REVENUE", "COST", "ASSETS", "CASH", "TAX", "INCOME"]
LINE = re.compile(r"^\s*(REVENUE|COST|ASSETS|CASH|TAX|INCOME)\s*[:=]?\s*([+-]?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?)\s+(USD|EUR|SHARES)\s+(\d{4}-\d{2}-\d{2})\s*$", re.M)


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def encode(value):
    return json.dumps(value, sort_keys=True, indent=2) + "\n"


def parse(text, page):
    return [{"page": page, "field": m[1], "value": format(Decimal(m[2].replace(",", "")).normalize(), "f"),
             "unit": m[3], "period": m[4]} for m in LINE.finditer(text)]


def validate(manifest, root):
    if manifest.get("schema_version") != 1 or not manifest.get("documents"):
        raise ValueError("non-empty schema_version 1 document manifest required")
    groups = {key: {} for key in ("document_id", "issuer", "template")}
    for doc in manifest["documents"]:
        if doc.get("split") not in ("train", "dev", "test") or not doc.get("family") or not doc.get("source_version"):
            raise ValueError("document family, split and source version required")
        for key in groups:
            value = doc.get(key)
            if not value or (value in groups[key] and (key == "document_id" or groups[key][value] != doc["split"])):
                raise ValueError(f"duplicate document or split leakage in {key}")
            groups[key][value] = doc["split"]
        pages = set()
        for page in doc["pages"]:
            if type(page["page"]) is not int or page["page"] < 1 or page["page"] in pages:
                raise ValueError("duplicate or invalid page")
            pages.add(page["page"])
            path = (root / page["image"]).resolve()
            if not path.is_relative_to(root.resolve()) or digest(path) != page["sha256"]:
                raise ValueError("page path or content hash mismatch")
            for fact in page["expected"]:
                if fact["page"] != page["page"]:
                    raise ValueError("label on wrong page")
            evaluate({"schema_version": 1, "cases": [{**doc, "expected": page["expected"], "observed": []}]})
        # A source version is bound to the ordered image bytes, not a friendly filename.
        version = hashlib.sha256("".join(p["sha256"] for p in doc["pages"]).encode()).hexdigest()
        if version != doc["source_version"]:
            raise ValueError("document source version hash mismatch")
    return manifest


def generate(root):
    """Original development data. Labels are generated, never human adjudicated."""
    from PIL import Image, ImageDraw, ImageFont, ImageFilter
    root.mkdir(parents=True, exist_ok=False)
    font = ImageFont.load_default(size=28)
    documents = []
    for family in ("income", "balance", "cashflow", "statement"):
        for index in range(10):
            split = "train" if index < 3 else "dev" if index < 5 else "test"
            doc = {"document_id": f"{family}-{index}", "issuer": f"fixture-{family}-{index}", "family": family,
                   "template": f"{family}-{split}", "split": split, "tenant_id": "fixture-tenant", "pages": []}
            for number in range(1, 6):
                lines, expected = [], []
                for field_index, field in enumerate(SCHEMA):
                    value = str(1000 + index * 101 + number * 17 + field_index * 29)
                    unit = "EUR" if number == 3 else "USD"
                    period = "2024-12-31" if number == 2 else "2025-12-31"
                    # Missing values are explicit negatives, not zero-valued facts.
                    absent = number == 5 and field_index == 5
                    lines.append(f"{field}: {'MISSING' if absent else value} {unit} {period}")
                    if not absent:
                        expected.append({"page": number, "field": field, "value": value, "unit": unit, "period": period})
                text = f"{family.upper()} {index} PAGE {number}\n" + "\n".join(lines)
                image = Image.new("L", (1150, 500), 255)
                draw = ImageDraw.Draw(image)
                x = 25 if split == "train" else 70 if split == "dev" else 130
                draw.multiline_text((x, 30), text, font=font, fill=170 if number == 4 else 0, spacing=14)
                if number == 4:
                    image = image.filter(ImageFilter.GaussianBlur(radius=0.8))
                filename = f'{doc["document_id"]}-p{number}.png'
                image.save(root / filename)
                doc["pages"].append({"page": number, "image": filename, "sha256": digest(root / filename),
                                     "expected": expected, "reference_text": text,
                                     "quality": "low_contrast_blurred" if number == 4 else "clean"})
            doc["source_version"] = hashlib.sha256("".join(p["sha256"] for p in doc["pages"]).encode()).hexdigest()
            documents.append(doc)
    manifest = {"schema_version": 1, "cohort": "controlled_development_fixtures", "rights": "Original generated text and labels; no third-party documents.",
                "label_origin": "programmatic, not independent human adjudication", "documents": documents}
    (root / "manifest.json").write_text(encode(manifest))


def freeze(manifest_path, protocol_path):
    manifest = validate(json.loads(manifest_path.read_text()), manifest_path.parent)
    protocol = {"schema_version": 1, "manifest_sha256": digest(manifest_path), "cohort": manifest["cohort"],
                "code_sha256": {p.name: digest(p) for p in (Path(__file__), Path(__file__).with_name("document_eval.py"), Path(__file__).with_name("ocr_eval.py"))},
                "split": "test", "unit": "document", "seed": 20261015, "bootstrap_repetitions": 1000,
                "ocr": {"binary": shutil.which("tesseract"), "psm": 6}, "warmups": 1, "repetitions": 1,
                "baseline": "historical global field/value membership", "intervention": "document/version/page/field/value/unit/period multiset",
                "controls": ["wrong_page", "wrong_unit", "wrong_period", "missing", "duplicate", "wrong_version"],
                "review_time": "unperformed, requires consenting reviewers and counterbalanced baseline/workflow sessions",
                "minimum_effect": "no uplift claim on controlled fixtures, descriptive diagnostic only"}
    with protocol_path.open("x") as out:
        out.write(encode(protocol))
    return protocol


def bootstrap(rows, seed, repetitions):
    rng = random.Random(seed)
    values = []
    for _ in range(repetitions):
        sample = rng.choices(rows, k=len(rows))
        values.append(sum(x[0] for x in sample) / sum(x[1] for x in sample))
    values.sort()
    return [values[int(repetitions * .025)], values[min(repetitions - 1, int(repetitions * .975))]]


def compare(cases):
    expected = [(f["field"], f["value"]) for c in cases for f in c["expected"]]
    observed = [(f["field"], f["value"]) for c in cases for f in c["observed"]]
    legacy_tp = sum(pair in observed for pair in expected)
    return {"historical_global_membership": {"matched": legacy_tp, "precision": legacy_tp / len(observed) if observed else 0,
                                               "recall": legacy_tp / len(expected) if expected else 0},
            "page_scoped": evaluate({"schema_version": 1, "cases": cases})}


def controls(cases):
    results = {}
    for name in ("valid", "wrong_page", "wrong_unit", "wrong_period", "missing", "duplicate", "wrong_version"):
        mutated = []
        for case in cases:
            observed = [dict(f) for f in case["expected"]]
            for f in observed:
                if name == "wrong_page": f["page"] = f["page"] % 5 + 1
                if name == "wrong_unit": f["unit"] = "SHARES"
                if name == "wrong_period": f["period"] = "1999-01-01"
                if name == "wrong_version": f["source_version"] = "stale"
            if name == "missing": observed = []
            if name == "duplicate": observed = observed + observed
            mutated.append({**case, "observed": observed})
        results[name] = compare(mutated)
    return results


def run(manifest_path, protocol_path, output):
    manifest = validate(json.loads(manifest_path.read_text()), manifest_path.parent)
    protocol = json.loads(protocol_path.read_text())
    if digest(manifest_path) != protocol["manifest_sha256"]:
        raise ValueError("frozen manifest changed")
    for name, sha in protocol["code_sha256"].items():
        if digest(Path(__file__).with_name(name)) != sha:
            raise ValueError("frozen study code changed")
    output.mkdir(parents=True, exist_ok=False)
    selected = [d for d in manifest["documents"] if d["split"] == protocol["split"]]
    if not selected:
        raise ValueError("empty evaluation split")
    binary = protocol["ocr"]["binary"]
    run_ocr(binary, manifest_path.parent / selected[0]["pages"][0]["image"])
    cases, raw, latency, families = [], [], [], defaultdict(list)
    for doc in selected:
        observed, expected = [], []
        for page in doc["pages"]:
            start = time.perf_counter()
            text = run_ocr(binary, manifest_path.parent / page["image"])
            elapsed = time.perf_counter() - start
            latency.append(elapsed)
            observed.extend(parse(text, page["page"]))
            expected.extend(page["expected"])
            raw.append({"document_id": doc["document_id"], "source_version": doc["source_version"], "page": page["page"],
                        "seconds": elapsed, "ocr_text": text, "quality": page["quality"]})
        case = {"document_id": doc["document_id"], "source_version": doc["source_version"], "expected": expected, "observed": observed}
        cases.append(case); families[doc["family"]].append(case)
    summary = compare(cases)
    paired = [(evaluate({"schema_version": 1, "cases": [c]})["matched_facts"], len(c["expected"])) for c in cases]
    summary.update({"protocol_sha256": digest(protocol_path), "cohort": manifest["cohort"], "documents": len(cases), "pages": len(raw),
                    "by_family": {k: compare(v) for k, v in families.items()}, "controls": controls(cases),
                    "document_cluster_recall_95_percentile_interval": bootstrap(paired, protocol["seed"], protocol["bootstrap_repetitions"]),
                    "ocr_seconds_p95": sorted(latency)[max(0, int(.95 * len(latency)) - 1)],
                    "review_routing": {"all_ocr_fields_require_review": sum(len(c["observed"]) for c in cases), "human_actions": None, "human_active_seconds": None},
                    "environment": {"platform": platform.platform(), "python": platform.python_version(),
                                    "tesseract": subprocess.check_output([binary, "--version"], text=True).splitlines()[0]},
                    "limitations": ["Controlled development fixtures and generated labels, not representative independent real-document accuracy.",
                                    "Latency is serialized by the external measurement lock, one repetition; no speed improvement claim.",
                                    "No reviewer-time, deployment, customer or independent review evidence."]})
    for name, value in (("observations.json", raw), ("cases.json", cases), ("summary.json", summary)):
        (output / name).write_text(encode(value))
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    sub.add_parser("generate").add_argument("directory", type=Path)
    for action in ("freeze", "run"):
        cmd = sub.add_parser(action)
        cmd.add_argument("manifest", type=Path); cmd.add_argument("protocol", type=Path)
        if action == "run": cmd.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.action == "generate": generate(args.directory)
    elif args.action == "freeze": freeze(args.manifest, args.protocol)
    else: run(args.manifest, args.protocol, args.output)

if __name__ == "__main__": main()
