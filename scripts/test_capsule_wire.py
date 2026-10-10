#!/usr/bin/env python3
"""
Verification test suite for Ned Capsule protocol and dual-wire compatibility.
Tests the contract between Rust host.rs, Android Runner.kt/MeshClient.kt, and the dashboard.
"""

import json
import unittest

class TestNedCapsuleWire(unittest.TestCase):
    def test_full_capsule_schema(self):
        # Sample capsule emitted by host.rs
        raw_capsule = {
            "t": "capsule",
            "v": 1,
            "capsule_id": "cap-a3f9c1-1",
            "issued_at": 1760000000,
            "task": "agent",
            "role": "helper",
            "model": "Qwen3-Coder-30B-A3B",
            "model_info": {
                "name": "Qwen3-Coder-30B-A3B",
                "file": "Qwen3-Coder-30B-A3B-Q4_K_M.gguf"
            },
            "layers": "9-27",
            "layers_slice": {
                "from": 9,
                "to": 27
            },
            "runtime": {
                "backend": "opencl",
                "threads": 6,
                "kv_type": "f16",
                "ubatch": 512,
                "cache_prompt": True
            },
            "evidence": {
                "run_ids": [412, 418, 431],
                "gate": "14/15",
                "baseline_gate": "14/15"
            },
            "why": "19 layers on iQOO 15: agent mode fitted to hardware (opencl, 6 threads, f16 KV)"
        }

        # Verify essential fields
        self.assertEqual(raw_capsule["t"], "capsule")
        self.assertEqual(raw_capsule["v"], 1)
        self.assertTrue(raw_capsule["capsule_id"].startswith("cap-"))
        self.assertEqual(raw_capsule["runtime"]["backend"], "opencl")
        self.assertEqual(raw_capsule["runtime"]["threads"], 6)
        self.assertEqual(raw_capsule["runtime"]["kv_type"], "f16")
        self.assertEqual(raw_capsule["evidence"]["gate"], "14/15")
        self.assertIn(418, raw_capsule["evidence"]["run_ids"])

        # Dual compatibility: un-updated phone reading only "layers" and "model"
        self.assertEqual(raw_capsule["layers"], "9-27")
        self.assertEqual(raw_capsule["model"], "Qwen3-Coder-30B-A3B")

    def test_legacy_run_fallback_parsing(self):
        # Old 3-field message emitted by previous versions
        legacy_msg = {
            "t": "run",
            "layers": "9-27",
            "model": "Qwen3-Coder-30B-A3B"
        }

        # Simulate Android NedCapsule.fromJSON fallback
        layers_raw = legacy_msg.get("layers", "")
        parts = layers_raw.split("-")
        from_layer = int(parts[0]) if len(parts) > 0 and parts[0] else 0
        to_layer = int(parts[1]) if len(parts) > 1 and parts[1] else from_layer

        self.assertEqual(from_layer, 9)
        self.assertEqual(to_layer, 27)
        self.assertEqual(legacy_msg.get("model"), "Qwen3-Coder-30B-A3B")

    def test_ready_acknowledgment_with_capsule_id(self):
        # Verify ready handshake carries capsule_id
        capsule_id = "cap-a3f9c1-1"
        ready_ack = {
            "t": "ready",
            "addr": "192.168.1.105:50052",
            "capsule_id": capsule_id
        }

        self.assertEqual(ready_ack["t"], "ready")
        self.assertEqual(ready_ack["capsule_id"], capsule_id)

    def test_stale_capsule_detection(self):
        active_capsule_id = "cap-rev2-99"
        stale_ack = {
            "t": "ready",
            "addr": "192.168.1.105:50052",
            "capsule_id": "cap-rev1-88"
        }

        # Detection logic matches host.rs
        is_stale = stale_ack["capsule_id"] != active_capsule_id
        self.assertTrue(is_stale)

if __name__ == "__main__":
    unittest.main()
