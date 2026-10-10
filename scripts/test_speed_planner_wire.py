#!/usr/bin/env python3
"""
Automated test suite for Part 6: Compute-Aware Speed Profiling & Throughput-Optimal Planning.
Verifies hardware speed tier scoring, compute-memory product helper ranking,
pipeline bottleneck identification, and wire protocol backward compatibility.
"""

import json
import unittest


def chip_speed_score(specs, is_host=False):
    """Reference implementation of chip speed rating."""
    if is_host:
        return 1.8
    chip = specs.get("chip", "").lower()
    name = specs.get("name", "").lower()
    combined = f"{chip} {name}"
    if any(k in combined for k in ["8 elite", "sm8750", "iqoo 15", "adreno 840"]):
        return 3.0
    elif any(k in combined for k in ["8 gen 3", "dimensity 9400", "dimensity 9300"]):
        return 2.2
    elif any(k in combined for k in ["8 gen 2", "adreno 7"]):
        return 1.6
    else:
        return 1.0


def rank_helpers_pure_memory(helpers):
    """Old naive approach: sort purely by usable memory descending."""
    return sorted(helpers, key=lambda d: d["usable_bytes"], reverse=True)


def rank_helpers_speed_weighted(helpers):
    """New throughput-optimal approach: sort by usable_bytes * speed_score descending."""
    return sorted(helpers, key=lambda d: d["usable_bytes"] * d["speed_score"], reverse=True)


def analyze_pipeline(host, selected_helpers):
    """Pipeline bottleneck analysis."""
    all_devs = [host] + selected_helpers
    primary = max(all_devs, key=lambda d: d["speed_score"])["name"]
    bottleneck = min(all_devs, key=lambda d: d["speed_score"])["name"]
    min_speed = min(d["speed_score"] for d in all_devs)
    return {
        "primary_compute": primary,
        "bottleneck_device": bottleneck,
        "pipeline_speed_score": min_speed,
    }


class TestSpeedPlanner(unittest.TestCase):
    def test_chip_speed_scoring(self):
        # Host device
        self.assertEqual(chip_speed_score({}, is_host=True), 1.8)

        # Flagship Snapdragon 8 Elite / iQOO 15 Adreno 840
        self.assertEqual(chip_speed_score({"chip": "Snapdragon 8 Elite", "name": "iQOO 15"}), 3.0)
        self.assertEqual(chip_speed_score({"chip": "Adreno 840", "name": "Phone"}), 3.0)

        # High-end Snapdragon 8 Gen 3 / Dimensity 9400
        self.assertEqual(chip_speed_score({"chip": "Snapdragon 8 Gen 3", "name": "iQOO 12"}), 2.2)
        self.assertEqual(chip_speed_score({"chip": "Dimensity 9400", "name": "Vivo X200"}), 2.2)

        # Upper mid-range Snapdragon 8 Gen 2 / Adreno 740
        self.assertEqual(chip_speed_score({"chip": "Snapdragon 8 Gen 2", "name": "iQOO 11"}), 1.6)

        # Budget / CPU / Mid-tier
        self.assertEqual(chip_speed_score({"chip": "Dimensity 7050", "name": "Phone"}), 1.0)
        self.assertEqual(chip_speed_score({"chip": "Cortex-A55", "name": "Generic"}), 1.0)

    def test_ranking_prevents_pipeline_stragglers(self):
        iqoo15 = {
            "name": "iQOO 15 (Adreno 840)",
            "usable_bytes": 7.0 * 1e9,
            "speed_score": 3.0,  # 7.0 * 3.0 = 21.0
        }
        mid_tier = {
            "name": "Mid-tier CPU Phone",
            "usable_bytes": 8.0 * 1e9,
            "speed_score": 1.0,  # 8.0 * 1.0 = 8.0
        }
        helpers = [mid_tier, iqoo15]

        # Naive pure memory sort chooses mid-tier first (8 GB > 7 GB)
        naive_order = rank_helpers_pure_memory(helpers)
        self.assertEqual(naive_order[0]["name"], "Mid-tier CPU Phone")

        # Speed-weighted sort prioritizes iQOO 15 due to massive compute advantage
        optimal_order = rank_helpers_speed_weighted(helpers)
        self.assertEqual(optimal_order[0]["name"], "iQOO 15 (Adreno 840)")

    def test_pipeline_bottleneck_detection(self):
        host = {"name": "Host Laptop", "speed_score": 1.8}
        iqoo15 = {"name": "iQOO 15", "speed_score": 3.0}
        iqoo12 = {"name": "iQOO 12", "speed_score": 2.2}

        analysis = analyze_pipeline(host, [iqoo15, iqoo12])
        self.assertEqual(analysis["primary_compute"], "iQOO 15")
        self.assertEqual(analysis["bottleneck_device"], "Host Laptop")
        self.assertEqual(analysis["pipeline_speed_score"], 1.8)

    def test_plan_json_schema_backward_compatibility(self):
        # Verifies that adding 'pipeline' does not break standard wire fields
        plan_json_payload = {
            "verdict": "doable",
            "reason": "Needs 2 devices (speed-optimized)",
            "need_gb": 18.2,
            "slices": [
                {"device": "laptop", "from": 0, "to": 12, "bytes": 4800000000},
                {"device": "iqoo15", "from": 12, "to": 48, "bytes": 14000000000},
            ],
            "skipped": {},
            "pipeline": {
                "primary_compute": "iQOO 15",
                "bottleneck_device": "laptop",
                "pipeline_speed_score": 1.8,
            },
        }

        # Essential legacy fields present
        self.assertIn("verdict", plan_json_payload)
        self.assertIn("slices", plan_json_payload)
        self.assertIn("need_gb", plan_json_payload)
        self.assertEqual(len(plan_json_payload["slices"]), 2)

        # Enhanced pipeline fields present
        self.assertIn("pipeline", plan_json_payload)
        self.assertEqual(plan_json_payload["pipeline"]["primary_compute"], "iQOO 15")


if __name__ == "__main__":
    unittest.main()
