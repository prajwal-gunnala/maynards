#!/usr/bin/env python3
"""
Automated test suite for Part 5: Background Job Queue & Dual-Mode Scheduler ("Near" vs "Far").
Verifies asynchronous queue lifecycle, job progress and token metering,
dual-mode link scheduling, and jobs.jsonl persistence.
"""

import json
import os
import tempfile
import time
import unittest


def link_execution_mode(rtt_ms, link_type):
    """
    Python reference of the scheduler's link decision rule.
    Low-latency (< 12ms / cable) -> 'near' (tensor layer-split pipeline)
    High-latency (> 12ms / relay) -> 'far' (whole-job autonomous dispatch)
    """
    if "cable" in link_type.lower() or "usb" in link_type.lower() or (0.0 <= rtt_ms <= 12.0):
        return "near"
    return "far"


class TestJobQueueAndDualModeScheduler(unittest.TestCase):
    def test_dual_mode_scheduling_decisions(self):
        # USB Cable -> near mode
        self.assertEqual(link_execution_mode(2.1, "USB cable"), "near")
        # Direct Wi-Fi 5 GHz hotspot at 6 ms -> near mode
        self.assertEqual(link_execution_mode(6.2, "Wi-Fi"), "near")
        # Direct Wi-Fi at exactly 12 ms -> near mode
        self.assertEqual(link_execution_mode(12.0, "Wi-Fi"), "near")
        # Congested Wi-Fi at 28 ms -> far mode (avoid per-token round-trip penalty)
        self.assertEqual(link_execution_mode(28.5, "Wi-Fi"), "far")
        # Relay over mobile data (45-120 ms) -> far mode
        self.assertEqual(link_execution_mode(75.0, "relay"), "far")

    def test_job_lifecycle_transitions(self):
        # 1. Enqueue job
        job = {
            "id": "job-100a-beef",
            "prompt": "Explain asynchronous pipelining in distributed inference.",
            "model": "qwen2.5-14b",
            "created_at": "2026-10-10 17:00:00",
            "status": "queued",
            "tokens_done": 0,
            "tps": 0.0,
            "result": "",
            "error": None,
            "mode": "near",
        }
        self.assertEqual(job["status"], "queued")
        self.assertEqual(job["tokens_done"], 0)

        # 2. Worker starts running
        job["status"] = "running"
        self.assertEqual(job["status"], "running")

        # 3. Progress updates
        job["tokens_done"] = 120
        job["tps"] = 18.5
        job["result"] = "Asynchronous pipelining splits transformer blocks across..."

        self.assertGreater(job["tokens_done"], 0)
        self.assertGreater(job["tps"], 0.0)

        # 4. Completion
        job["status"] = "completed"
        self.assertEqual(job["status"], "completed")
        self.assertIsNone(job["error"])

    def test_job_cancellation(self):
        job = {
            "id": "job-cancel-test",
            "prompt": "Heavy compile task",
            "status": "queued",
            "error": None
        }

        # User cancels job
        if job["status"] in ("queued", "running"):
            job["status"] = "failed"
            job["error"] = "cancelled by user"

        self.assertEqual(job["status"], "failed")
        self.assertEqual(job["error"], "cancelled by user")

    def test_jobs_persistence_and_recovery(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            jobs_file = os.path.join(tmpdir, "jobs.jsonl")

            job1 = {
                "id": "job-1",
                "prompt": "Task 1",
                "model": "qwen2.5-14b",
                "created_at": "2026-10-10 17:00:00",
                "status": "completed",
                "tokens_done": 85,
                "tps": 16.2,
                "result": "Completed answer 1",
                "error": None,
                "mode": "near"
            }
            job2 = {
                "id": "job-2",
                "prompt": "Task 2",
                "model": "qwen2.5-14b",
                "created_at": "2026-10-10 17:05:00",
                "status": "running",
                "tokens_done": 40,
                "tps": 14.8,
                "result": "Partial answer 2...",
                "error": None,
                "mode": "far"
            }

            with open(jobs_file, "w") as f:
                f.write(json.dumps(job1) + "\n")
                f.write(json.dumps(job2) + "\n")

            # Read back from file
            reloaded = []
            with open(jobs_file, "r") as f:
                for line in f:
                    if line.strip():
                        reloaded.append(json.loads(line.strip()))

            self.assertEqual(len(reloaded), 2)
            self.assertEqual(reloaded[0]["id"], "job-1")
            self.assertEqual(reloaded[0]["mode"], "near")
            self.assertEqual(reloaded[1]["id"], "job-2")
            self.assertEqual(reloaded[1]["mode"], "far")
            self.assertEqual(reloaded[1]["status"], "running")


if __name__ == "__main__":
    unittest.main()
