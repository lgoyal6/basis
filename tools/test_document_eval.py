"""Check document_eval provenance keys and multiset matching.
Used with the document study scorer before any comparative run.
Wrong pages, source versions, units and periods must not match valid facts.
"""
import unittest

from document_eval import evaluate


def fact(page, value, unit="USD", period="2025-12-31"):
    return {"page": page, "field": "revenue", "value": value, "unit": unit, "period": period}


class DocumentEvalTest(unittest.TestCase):
    def test_wrong_page_does_not_match_same_fact_on_another_page(self):
        result = evaluate({"schema_version": 1, "cases": [{
            "document_id": "doc-a", "source_version": "sha256:a",
            "expected": [fact(1, "100"), fact(2, "200")],
            "observed": [fact(1, "200"), fact(2, "100")],
        }]})
        self.assertEqual(result["matched_facts"], 0)
        self.assertEqual(result["field_recall"], 0.0)
        self.assertEqual(result["wrong_page_or_value_facts"], 2)

    def test_units_periods_and_duplicate_counts_are_part_of_the_fact(self):
        result = evaluate({"schema_version": 1, "cases": [{
            "document_id": "doc-a", "source_version": "sha256:a",
            "expected": [fact(1, "100"), fact(1, "100")],
            "observed": [fact(1, "100", unit="EUR"), fact(1, "100")],
        }]})
        self.assertEqual(result["matched_facts"], 1)
        self.assertEqual(result["field_precision"], 0.5)
        self.assertEqual(result["field_recall"], 0.5)

    def test_missing_source_version_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "source_version"):
            evaluate({"schema_version": 1, "cases": [{"document_id": "doc-a", "expected": [fact(1, "1")], "observed": []}]})

    def test_boolean_page_is_not_a_page_number(self):
        with self.assertRaisesRegex(ValueError, "positive integer"):
            evaluate({"schema_version": 1, "cases": [{
                "document_id": "doc-a", "source_version": "sha256:a",
                "expected": [fact(True, "100")], "observed": [],
            }]})


if __name__ == "__main__":
    unittest.main()
