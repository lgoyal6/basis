"""Validate externally collected, consenting reviewer session records.
Consumes a locked protocol and active-time, correction and export observations.
Receipt references require independent checking and never create human evidence.
"""
from __future__ import annotations
import argparse
import json
import math
import re
from collections import defaultdict
from pathlib import Path


def analyze(protocol, observations):
    if protocol.get("schema_version") != 1 or not protocol.get("tasks") or not protocol.get("participants"):
        raise ValueError("locked protocol needs tasks and participants")
    if protocol.get("frozen_before_collection") is not True:
        raise ValueError("protocol must be frozen before collection")
    assignments = protocol["participants"]
    if any(v not in (["baseline", "workflow"], ["workflow", "baseline"]) for v in assignments.values()):
        raise ValueError("each participant needs a counterbalanced condition order")
    seen, rows = set(), []
    for session in observations:
        participant, task, condition = (session.get(k) for k in ("participant_id", "task_id", "condition"))
        if participant not in assignments or task not in protocol["tasks"] or condition not in assignments[participant]:
            raise ValueError("unregistered session")
        key = (participant, task, condition)
        if key in seen: raise ValueError("duplicate participant/task/condition session")
        seen.add(key)
        if session.get("consent_receipt") not in protocol.get("consent_receipts", []) or not session.get("consent_receipt"):
            raise ValueError("session requires a bound consent receipt")
        if session.get("cohort") != "consenting_human" or not session.get("raw_receipt_sha256"):
            raise ValueError("human cohort and raw evidence hash required")
        if not re.fullmatch(r"[0-9a-f]{64}", session["raw_receipt_sha256"]):
            raise ValueError("raw receipt requires a SHA-256 digest")
        if session.get("condition_order") != assignments[participant].index(condition):
            raise ValueError("session condition order differs from locked assignment")
        intervals = session.get("active_intervals_seconds")
        if not isinstance(intervals, list) or not intervals:
            raise ValueError("active intervals required, idle time excluded")
        last = -1
        active = 0
        for start, end in intervals:
            if (type(start) not in (int, float) or type(end) not in (int, float)
                    or not math.isfinite(start) or not math.isfinite(end) or start < 0 or start < last or end < start):
                raise ValueError("active intervals must be ordered, disjoint and nonnegative")
            active += end - start; last = end
        corrections, unsafe = session.get("correction_actions"), session.get("unsafe_exports")
        if type(corrections) is not int or corrections < 0 or type(unsafe) is not int or unsafe < 0 or type(session.get("completed")) is not bool:
            raise ValueError("explicit completion and nonnegative action/export counts required")
        rows.append({"participant_id": participant, "task_id": task, "condition": condition,
                     "active_seconds": active, "corrections": corrections, "unsafe_exports": unsafe, "completed": session["completed"]})
    by_condition = {}
    for condition in ("baseline", "workflow"):
        selected = [r for r in rows if r["condition"] == condition]
        by_condition[condition] = {"sessions": len(selected), "completed_tasks": sum(r["completed"] for r in selected),
                                   "active_seconds": sum(r["active_seconds"] for r in selected), "corrections": sum(r["corrections"] for r in selected),
                                   "unsafe_exports": sum(r["unsafe_exports"] for r in selected)}
    pairs = []
    for participant, task in sorted({(r["participant_id"], r["task_id"]) for r in rows}):
        baseline = next((r for r in rows if (r["participant_id"], r["task_id"], r["condition"]) == (participant, task, "baseline")), None)
        workflow = next((r for r in rows if (r["participant_id"], r["task_id"], r["condition"]) == (participant, task, "workflow")), None)
        if baseline and workflow:
            pairs.append({"participant_id": participant, "task_id": task, "workflow_minus_baseline_active_seconds": workflow["active_seconds"] - baseline["active_seconds"]})
    repeat = defaultdict(set)
    for row in rows:
        if row["condition"] == "workflow" and row["completed"]: repeat[row["participant_id"]].add(row["task_id"])
    return {"sessions": len(rows), "independent_participants": len({r["participant_id"] for r in rows}), "by_condition": by_condition,
            "paired_tasks": pairs, "participants_with_repeated_completed_workflow_tasks": sum(len(tasks) > 1 for tasks in repeat.values()),
            "limitations": ["Receipt strings are references that an independent reviewer must verify; presence does not authenticate a human or consent.",
                            "Descriptive paired results only; repeated sessions are not independent participants. No uplift or significance inferred."]}

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("protocol", type=Path); parser.add_argument("observations", type=Path)
    args = parser.parse_args()
    print(json.dumps(analyze(json.loads(args.protocol.read_text()), json.loads(args.observations.read_text())), indent=2))
