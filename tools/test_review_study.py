"""Check reviewer-record validation with explicit test-only sessions.
Exercises review_study pairing, active intervals and consent references.
These records test analysis code and never count as human participation.
"""
import unittest
from review_study import analyze


class ReviewStudyTest(unittest.TestCase):
    def setUp(self):
        self.protocol = {"schema_version": 1, "frozen_before_collection": True, "tasks": ["t"],
                         "participants": {"test-only": ["baseline", "workflow"]}, "consent_receipts": ["test-only-receipt"]}
        self.session = {"participant_id": "test-only", "task_id": "t", "condition": "baseline", "cohort": "consenting_human",
                        "consent_receipt": "test-only-receipt", "raw_receipt_sha256": "0" * 64, "condition_order": 0,
                        "active_intervals_seconds": [[0, 10], [20, 25]], "correction_actions": 2, "unsafe_exports": 0, "completed": True}

    def test_repeated_sessions_do_not_inflate_participant_or_idle_time(self):
        output = analyze(self.protocol, [self.session, dict(self.session, condition="workflow", condition_order=1, active_intervals_seconds=[[0, 20]])])
        self.assertEqual(output["independent_participants"], 1)
        self.assertEqual(output["by_condition"]["baseline"]["active_seconds"], 15)
        self.assertEqual(output["paired_tasks"][0]["workflow_minus_baseline_active_seconds"], 5)

    def test_missing_consent_and_duplicate_observation_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "consent"): analyze(self.protocol, [dict(self.session, consent_receipt="")])
        with self.assertRaisesRegex(ValueError, "duplicate"): analyze(self.protocol, [self.session, self.session])
        with self.assertRaisesRegex(ValueError, "interval"): analyze(self.protocol, [dict(self.session, active_intervals_seconds=[[0, 10], [9, 12]])])
        with self.assertRaisesRegex(ValueError, "human cohort"): analyze(self.protocol, [dict(self.session, cohort="synthetic")])
        with self.assertRaisesRegex(ValueError, "interval"): analyze(self.protocol, [dict(self.session, active_intervals_seconds=[[0, float("nan")]])])
        with self.assertRaisesRegex(ValueError, "condition order"): analyze(self.protocol, [dict(self.session, condition_order=1)])
