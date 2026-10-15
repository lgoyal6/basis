#!/usr/bin/env python3
"""Score document facts with page, unit, period and source identity.
Used by document_study and its provenance failure controls.
Multiset matching keeps duplicates and misplaced facts from becoming true matches.
"""
from __future__ import annotations

from collections import Counter, defaultdict
from typing import Any


FACT_FIELDS = ("page", "field", "value", "unit", "period")


def _fact_key(case: dict[str, Any], fact: dict[str, Any]) -> tuple:
    missing = [name for name in FACT_FIELDS if name not in fact or fact[name] in (None, "")]
    if missing:
        raise ValueError(f"fact missing required provenance/value fields: {', '.join(missing)}")
    if type(fact["page"]) is not int or fact["page"] < 1:
        raise ValueError("fact page must be a positive integer")
    document = fact.get("document_id", case.get("document_id"))
    version = fact.get("source_version", case.get("source_version"))
    if not document or not version:
        raise ValueError("case requires document_id and source_version")
    return document, version, *(fact[name] for name in FACT_FIELDS)


def evaluate(manifest: dict[str, Any]) -> dict[str, Any]:
    if manifest.get("schema_version") != 1 or not isinstance(manifest.get("cases"), list) or not manifest["cases"]:
        raise ValueError("manifest requires schema_version 1 and a non-empty cases list")
    expected: Counter = Counter()
    observed: Counter = Counter()
    per_page: dict[tuple[str, str, int], dict[str, Counter]] = defaultdict(lambda: {"expected": Counter(), "observed": Counter()})
    identities = set()
    for case in manifest["cases"]:
        identity = (case.get("document_id"), case.get("source_version"))
        if not all(identity) or identity in identities:
            raise ValueError("cases require unique document_id and source_version")
        identities.add(identity)
        for name, target in (("expected", expected), ("observed", observed)):
            facts = case.get(name)
            if not isinstance(facts, list):
                raise ValueError(f"case {name} must be a list")
            for fact in facts:
                key = _fact_key(case, fact)
                target[key] += 1
                per_page[(key[0], key[1], key[2])][name][key] += 1

    true_positive = sum((expected & observed).values())
    expected_count, observed_count = sum(expected.values()), sum(observed.values())
    pages = []
    for (document, version, page), counts in sorted(per_page.items()):
        tp = sum((counts["expected"] & counts["observed"]).values())
        e, o = sum(counts["expected"].values()), sum(counts["observed"].values())
        pages.append({"document_id": document, "source_version": version, "page": page,
                      "expected": e, "observed": o, "matched": tp,
                      "precision": tp / o if o else 0.0, "recall": tp / e if e else 0.0})
    return {
        "schema_version": 1,
        "cases": len(manifest["cases"]),
        "expected_facts": expected_count,
        "observed_facts": observed_count,
        "matched_facts": true_positive,
        "field_precision": true_positive / observed_count if observed_count else 0.0,
        "field_recall": true_positive / expected_count if expected_count else 0.0,
        "wrong_page_or_value_facts": observed_count - true_positive,
        "page_results": pages,
        "limitations": ["This scorer checks labels and provenance only; it does not establish document representativeness or reviewer effort."],
    }
