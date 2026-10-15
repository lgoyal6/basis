"""Check document study inputs, parser and frozen failure controls.
Uses document_eval and disposable generated development fixtures.
Leakage or changed frozen inputs must fail before OCR starts.
"""
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from document_eval import evaluate
from document_study import controls, parse, validate, freeze, run, generate


def fixture():
    return {"document_id": "a", "source_version": "v1", "expected": [
        {"page": 1, "field": "REVENUE", "value": "100", "unit": "USD", "period": "2025-12-31"},
        {"page": 2, "field": "REVENUE", "value": "200", "unit": "EUR", "period": "2024-12-31"}], "observed": []}


class DocumentStudyTest(unittest.TestCase):
    def test_controls_expose_historical_false_matches(self):
        result = controls([fixture()])
        self.assertEqual(result["valid"]["page_scoped"]["field_recall"], 1)
        for axis in ("wrong_page", "wrong_unit", "wrong_period", "missing", "wrong_version"):
            self.assertEqual(result[axis]["page_scoped"]["matched_facts"], 0)
        self.assertEqual(result["wrong_page"]["historical_global_membership"]["recall"], 1)
        self.assertEqual(result["duplicate"]["page_scoped"]["field_precision"], .5)

    def test_parser_preserves_units_dates_and_signed_decimal_values(self):
        text = "REVENUE: 1,250.00 USD 2025-12-31\nCOST: -3.50 EUR 2024-12-31\nINCOME: MISSING USD 2025-12-31\nunknown 999\n"
        fields = parse(text, 3)
        self.assertEqual([f["value"] for f in fields], ["1250", "-3.5"])
        self.assertEqual([f["unit"] for f in fields], ["USD", "EUR"])
        self.assertTrue(all(f["page"] == 3 for f in fields))

    def test_parser_rejects_malformed_digit_grouping(self):
        self.assertEqual(parse("REVENUE: 1,,250 USD 2025-12-31\nCASH: 1,25 USD 2025-12-31\n", 1), [])

    def test_wrong_document_or_version_never_inherits_case_identity(self):
        case = fixture(); case["observed"] = [dict(case["expected"][0], source_version="stale")]
        self.assertEqual(evaluate({"schema_version": 1, "cases": [case]})["matched_facts"], 0)
        case["observed"] = [dict(case["expected"][0], document_id="other")]
        self.assertEqual(evaluate({"schema_version": 1, "cases": [case]})["matched_facts"], 0)
        with self.assertRaisesRegex(ValueError, "unique"):
            evaluate({"schema_version": 1, "cases": [case, case]})

    def test_fixture_splits_hashes_and_frozen_inputs_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "data"; generate(root)
            path = root / "manifest.json"; manifest = json.loads(path.read_text())
            validate(manifest, root)
            self.assertEqual(len(manifest["documents"]), 40)
            leaked = copy.deepcopy(manifest); leaked["documents"][3]["issuer"] = leaked["documents"][0]["issuer"]
            with self.assertRaisesRegex(ValueError, "split leakage"): validate(leaked, root)
            wrong = copy.deepcopy(manifest); wrong["documents"][0]["pages"][0]["image"] = "../escape.png"
            with self.assertRaisesRegex(ValueError, "hash mismatch"): validate(wrong, root)
            protocol = Path(temporary) / "protocol.json"; freeze(path, protocol)
            with self.assertRaises(FileExistsError): freeze(path, protocol)
            value = json.loads(protocol.read_text()); value["manifest_sha256"] = "tampered"; protocol.write_text(json.dumps(value))
            with patch("document_study.run_ocr") as ocr:
                with self.assertRaisesRegex(ValueError, "frozen manifest"): run(path, protocol, Path(temporary) / "out")
                ocr.assert_not_called()
