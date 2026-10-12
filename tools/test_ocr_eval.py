#!/usr/bin/env python3
import unittest

from ocr_eval import extract_fields


class OcrEvalTest(unittest.TestCase):
    def test_extracts_declared_fields_and_normalizes_commas(self):
        text = "TOTAL REVENUE: 1,250\nignored 999\nNET INCOME 840\n"
        self.assertEqual(
            extract_fields(text, {"TOTAL REVENUE", "NET INCOME"}),
            [("TOTAL REVENUE", "1250"), ("NET INCOME", "840")],
        )

    def test_does_not_turn_unconfigured_text_into_a_fact(self):
        self.assertEqual(extract_fields("PRIVATE ACCOUNT 42\n", {"TOTAL REVENUE"}), [])


if __name__ == "__main__":
    unittest.main()
