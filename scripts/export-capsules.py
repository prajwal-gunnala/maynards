#!/usr/bin/env python3
"""Turn Mesh Compiler results into Ned Capsule files the MeshAI app reads.

Usage: scripts/export-capsules.py [--db /mnt/storage/meshai/tuner/runs.sqlite] [--out capsules/] [--push SERIAL ...]

For each (phone model, model file) measured by the Mesh Compiler (github.com/prajwal-gunnala/mesh-compiler),
and for each task size it scores (chat = 100 in / 150 out, agent = 2,000 in / 300 out, from tuner/tasks.py),
the capsule names the configuration with the lowest task time among those that ran cleanly and passed the
quality gate at least as well as the baseline. A winner must beat the baseline by more than 3% (the
compiler's noise guard); otherwise the baseline is the capsule. Every capsule carries its evidence: the run
ids, quality score and both task times. --push copies them to the app's files/capsules/ folder over adb.
"""
import argparse
import json
import sqlite3
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

TASKS = {"chat": (100, 150), "agent": (2000, 300)}
NOISE = 0.03
ENGINE_BUILD = "66fba63"


def load(db):
    c = sqlite3.connect(db)
    rows = c.execute("select id, phase, label, device, model, model_bytes, engine, config, test, tps, extra, ok "
                     "from runs where ok = 1").fetchall()
    configs = defaultdict(lambda: {"tests": defaultdict(list), "ids": [], "quality": None, "labels": set()})
    for rid, phase, label, device, model, mbytes, engine, config, test, tps, extra, ok in rows:
        if phase not in ("baseline", "confirm", "quality", "screen", "search", "calibration"):
            continue
        cfg = json.loads(config or "{}")
        key = (device, model, json.dumps(cfg, sort_keys=True))
        e = configs[key]
        e["bytes"] = mbytes
        e["cfg"] = cfg
        e["labels"].add(label)
        e["ids"].append(rid)
        if test == "quality":
            q = json.loads(extra or "{}")
            if q.get("passed") is not None:
                e["quality"] = (q["passed"], q.get("passed", 0) + len(q.get("failed", [])))
        elif tps:
            e["tests"][test].append(tps)
    return configs


def speeds(e):
    mean = lambda xs: sum(xs) / len(xs) if xs else None
    pp = mean(e["tests"].get("pp512", []))
    pp2k = mean(e["tests"].get("pp2048", [])) or pp
    tg = mean(e["tests"].get("tg128", []))
    return pp, pp2k, tg


def task_time(task, e):
    pp, pp2k, tg = speeds(e)
    n_in, n_out = TASKS[task]
    read = pp2k if n_in > 512 else pp
    if not read or not tg:
        return None
    return n_in / read + n_out / tg


def is_baseline(e):
    return any("baseline" in l.lower() for l in e["labels"])


def capsules(configs):
    by_model = defaultdict(list)
    for (device, model, _), e in configs.items():
        by_model[(device, model)].append(e)
    out = []
    for (device, model), entries in by_model.items():
        base = next((e for e in entries if is_baseline(e) and e["quality"]), None) or next((e for e in entries if is_baseline(e)), None)
        if base is None:
            continue
        base_q = base["quality"][0] if base["quality"] else None
        # "any": the best measured setting; "cpu": the best one a CPU-only build (MeshAI's APK) can run.
        for task, scope in [(t, s) for t in TASKS for s in ("any", "cpu")]:
            bt = task_time(task, base)
            if bt is None:
                continue
            eligible = [e for e in entries if task_time(task, e) is not None and e["quality"] is not None
                        and (base_q is None or e["quality"][0] >= base_q)
                        and (scope == "any" or e["cfg"].get("engine", "cpu") == "cpu")]
            best = min(eligible, key=lambda e: task_time(task, e), default=base)
            wt = task_time(task, best)
            winner = best if wt < bt * (1 - NOISE) else base
            q = winner["quality"]
            out.append({
                "kind": "ned-capsule", "version": 1, "source": "mesh-compiler",
                "device": device, "model_file": model, "model_bytes": winner.get("bytes"), "task": task, "scope": scope,
                "engine_build": ENGINE_BUILD,
                "enact": {k: winner["cfg"][k] for k in ("engine", "threads", "ctk", "ctv", "ub", "b", "fa", "ngl") if k in winner["cfg"]},
                "evidence": {
                    "run_ids": sorted(winner["ids"]),
                    "label": sorted(winner["labels"])[0],
                    "quality": f"{q[0]}/{q[1]}" if q else None,
                    "task_seconds": round(task_time(task, winner), 2),
                    "baseline_task_seconds": round(bt, 2),
                    "is_baseline": winner is base,
                },
            })
    # a cpu capsule identical to the any capsule adds nothing
    seen, unique = set(), []
    for c in out:
        k = (c["device"], c["model_file"], c["task"], json.dumps(c["enact"], sort_keys=True))
        if k not in seen:
            seen.add(k); unique.append(c)
    return unique


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--db", default="/mnt/storage/meshai/tuner/runs.sqlite")
    ap.add_argument("--out", default="capsules")
    ap.add_argument("--push", nargs="*", default=[])
    a = ap.parse_args()
    caps = capsules(load(a.db))
    if not caps:
        sys.exit("no measured configuration with a baseline in " + a.db)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    for c in caps:
        name = f"{c['device']}__{c['model_file'].removesuffix('.gguf')}__{c['task']}__{c['scope']}.json"
        (out / name).write_text(json.dumps(c, indent=2) + "\n")
        ev = c["evidence"]
        print(f"{name}: {c['enact'].get('engine')} · {ev['task_seconds']} s vs baseline {ev['baseline_task_seconds']} s · quality {ev['quality']} · runs {ev['run_ids'][:4]}…")
    for serial in a.push:
        dest = "/sdcard/Android/data/ai.maynards.mesh/files/capsules/"
        subprocess.run(["adb", "-s", serial, "shell", "mkdir", "-p", dest], check=True)
        for f in out.glob("*.json"):
            subprocess.run(["adb", "-s", serial, "push", str(f), dest], check=True, capture_output=True)
        print(f"pushed {len(list(out.glob('*.json')))} capsule(s) to {serial}")


if __name__ == "__main__":
    main()
