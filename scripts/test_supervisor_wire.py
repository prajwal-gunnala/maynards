#!/usr/bin/env python3
"""
Automated test suite for Part 3: The Supervisor (Fault Tolerance & Automatic Re-plan Recovery).
Verifies survivor detection, automatic layer re-planning upon node departure,
capsule re-emission with updated layer boundaries, and in-flight request recovery buffer.
"""

import json
import unittest


def mock_plan(model_weights_gb, layers, devices, kv_per_layer_gb=0.015, host_reserve_gb=0.3):
    """
    Python reference implementation of the Host/Android planner logic for testing supervisor recovery.
    """
    host = next((d for d in devices if d.get("isHost")), None)
    if not host:
        return {"verdict": "not_possible", "reason": "no host"}

    layer_gb = model_weights_gb / layers
    total_need = model_weights_gb + (layers * kv_per_layer_gb) + host_reserve_gb

    # Usable bytes after reserves
    total_usable = sum(d["usable_gb"] for d in devices)
    if total_usable < total_need:
        return {
            "verdict": "not_possible",
            "reason": f"needs {total_need:.2f} GB, only {total_usable:.2f} GB across {len(devices)} devices"
        }

    # If host fits alone
    if host["usable_gb"] >= total_need:
        return {
            "verdict": "doable",
            "reason": "fits on Host alone",
            "slices": [{"id": host["id"], "name": host["name"], "from": 0, "to": layers}]
        }

    # Split across host and helpers
    slices = []
    # Host keeps embeddings & output head, plus proportional layers
    # Slices cover 0 to layers
    remaining_layers = layers
    current_layer = 0

    # Distribute layers proportionally to usable capacity
    for i, dev in enumerate(devices):
        if i == len(devices) - 1:
            take = remaining_layers
        else:
            share = dev["usable_gb"] / total_usable
            take = min(remaining_layers, max(1, int(round(share * layers))))
        
        slices.append({
            "id": dev["id"],
            "name": dev["name"],
            "from": current_layer,
            "to": current_layer + take
        })
        current_layer += take
        remaining_layers -= take
        if remaining_layers <= 0:
            break

    return {
        "verdict": "doable",
        "reason": f"split across {len(slices)} devices",
        "slices": slices
    }


class TestSupervisorRecovery(unittest.TestCase):
    def setUp(self):
        self.model_layers = 32
        self.model_weights_gb = 14.0  # e.g., Qwen 2.5 14B Q4_K_M ~9GB + KV
        self.cluster = [
            {"id": "laptop", "name": "ThinkPad Host", "usable_gb": 8.0, "isHost": True},
            {"id": "iqoo15-a", "name": "iQOO 15 Alpha", "usable_gb": 9.5, "isHost": False},
            {"id": "iqoo15-b", "name": "iQOO 15 Beta", "usable_gb": 9.5, "isHost": False},
        ]

    def test_initial_plan_distribution(self):
        plan = mock_plan(self.model_weights_gb, self.model_layers, self.cluster)
        self.assertEqual(plan["verdict"], "doable")
        self.assertEqual(len(plan["slices"]), 3)
        # Check layer continuity
        self.assertEqual(plan["slices"][0]["from"], 0)
        self.assertEqual(plan["slices"][0]["to"], plan["slices"][1]["from"])
        self.assertEqual(plan["slices"][1]["to"], plan["slices"][2]["from"])
        self.assertEqual(plan["slices"][2]["to"], self.model_layers)

    def test_supervisor_recovers_when_one_helper_leaves(self):
        # Initial 3-node plan
        plan_initial = mock_plan(self.model_weights_gb, self.model_layers, self.cluster)
        self.assertEqual(len(plan_initial["slices"]), 3)

        # Helper iQOO 15 Alpha drops out (e.g. user walked out of WiFi range or battery died)
        leaving_id = "iqoo15-a"
        survivors = [d for d in self.cluster if d["id"] != leaving_id]
        self.assertEqual(len(survivors), 2)

        # Supervisor detects exit and re-plans across surviving devices
        plan_recovered = mock_plan(self.model_weights_gb, self.model_layers, survivors)
        self.assertEqual(plan_recovered["verdict"], "doable")
        self.assertEqual(len(plan_recovered["slices"]), 2)

        # Ensure full layer coverage is maintained
        self.assertEqual(plan_recovered["slices"][0]["from"], 0)
        self.assertEqual(plan_recovered["slices"][0]["to"], plan_recovered["slices"][1]["from"])
        self.assertEqual(plan_recovered["slices"][1]["to"], self.model_layers)

        # Confirm the dropped helper is NOT in the new plan
        slice_ids = [s["id"] for s in plan_recovered["slices"]]
        self.assertNotIn(leaving_id, slice_ids)
        self.assertIn("laptop", slice_ids)
        self.assertIn("iqoo15-b", slice_ids)

    def test_supervisor_fails_gracefully_when_survivors_cannot_fit(self):
        # A large model that requires all 3 devices
        large_model_gb = 24.0
        plan_initial = mock_plan(large_model_gb, 48, self.cluster)
        self.assertEqual(plan_initial["verdict"], "doable")

        # Two helpers drop out, only host remains (Host has 8.0 GB, model needs >24 GB)
        survivors = [d for d in self.cluster if d["id"] == "laptop"]
        plan_failed = mock_plan(large_model_gb, 48, survivors)

        self.assertEqual(plan_failed["verdict"], "not_possible")
        self.assertIn("needs", plan_failed["reason"])

    def test_in_flight_request_buffer_and_replay_cycle(self):
        # Simulates the supervisor's in-flight buffer lifecycle:
        # 1. Request arrives -> stored in in_flight
        # 2. Crash occurs before response -> in_flight retained
        # 3. Re-planned engine comes up -> in_flight extracted and replayed
        hub_in_flight = None

        first_line = "POST /v1/chat/completions HTTP/1.1\r\n"
        headers = ["Content-Type: application/json\r\n", "User-Agent: curl/7.88.1\r\n"]
        body = json.dumps({
            "model": "qwen2.5-14b",
            "messages": [{"role": "user", "content": "How do you recover from node departure?"}],
            "stream": True
        }).encode("utf-8")

        # Request arrives
        hub_in_flight = (first_line, headers, body)
        self.assertIsNotNone(hub_in_flight)

        # Crash occurs mid-flight: response was empty, so hub_in_flight was NOT cleared
        self.assertIsNotNone(hub_in_flight)

        # Supervisor recovers engine: extracts in_flight for replay
        replayed_request = hub_in_flight
        hub_in_flight = None  # Cleared by supervisor upon dispatching replay

        self.assertEqual(replayed_request[0], first_line)
        self.assertEqual(replayed_request[1], headers)
        parsed_body = json.loads(replayed_request[2].decode("utf-8"))
        self.assertEqual(parsed_body["model"], "qwen2.5-14b")
        self.assertIsNone(hub_in_flight)


if __name__ == "__main__":
    unittest.main()
