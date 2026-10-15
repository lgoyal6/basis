"""Check the LangChain lexical source-finding baseline.
Exercises source_finding retrieval and returned page/version metadata.
Missing provenance fails closed; this is local SDK evidence only.
"""
import unittest

from source_finding import find_sources


class SourceFindingTest(unittest.TestCase):
    def test_langchain_runnable_retrieves_page_and_version_provenance(self):
        result = find_sources([
            {"document_id": "filing-a", "source_version": "sha256:one", "page": 1, "text": "Revenue was 100 dollars."},
            {"document_id": "filing-a", "source_version": "sha256:one", "page": 2, "text": "Operating costs were 40 dollars."},
        ], "revenue 100 dollars", k=1)
        self.assertEqual(len(result["sources"]), 1)
        self.assertEqual(result["sources"][0]["page"], 1)
        self.assertEqual(result["sources"][0]["source_version"], "sha256:one")
        self.assertIn("langchain", result["retrieval"])

    def test_missing_provenance_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "source_version"):
            find_sources([{"document_id": "filing-a", "page": 1, "text": "revenue 100"}], "revenue")

    def test_boolean_page_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "positive integer"):
            find_sources([{"document_id": "filing-a", "source_version": "v1", "page": True, "text": "revenue"}], "revenue")


if __name__ == "__main__":
    unittest.main()
