#!/usr/bin/env python3
"""
Measure what a configuration of the mesh is actually worth.

Asks an OpenAI-compatible endpoint to solve a set of small coding problems, runs the
answers against their tests, and records how many passed and how fast they came out.
Point it at one device, at a split across several, or at a cloud endpoint: the
comparison is only fair if the task set and the scoring are identical, so they are.

    scripts/bench.py --label "phone alone, 8B"      --url http://127.0.0.1:8080/v1
    scripts/bench.py --label "laptop + 2 phones, 30B" --url http://127.0.0.1:8080/v1
    scripts/bench.py --label "cloud, gpt-5-mini"   --url https://api.openai.com/v1 --key $KEY --model gpt-5-mini

Results go to results/<label>.json. scripts/bench-report.py turns them into a table
and a chart.

Standard library only, so it runs at a venue with no internet and no pip.
"""

import argparse
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
TASKS = HERE / "bench-tasks.jsonl"
RESULTS = HERE.parent / "results"


def load_tasks(path, limit):
    tasks = [json.loads(l) for l in open(path) if l.strip()]
    return tasks[:limit] if limit else tasks


def ask(url, key, model, prompt, timeout):
    """One chat request. Returns (text, seconds, tokens, tok/s)."""
    body = json.dumps({
        "model": model,
        "messages": [
            {"role": "system", "content":
             "You are a careful Python programmer. Reply with one complete function "
             "inside a ```python code block. No explanation, no tests, no examples."},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0,
        "max_tokens": 700,
        "stream": False,
    }).encode()
    headers = {"content-type": "application/json"}
    if key:
        headers["authorization"] = f"Bearer {key}"
    req = urllib.request.Request(url.rstrip("/") + "/chat/completions", data=body, headers=headers)

    t0 = time.time()
    with urllib.request.urlopen(req, timeout=timeout) as r:
        d = json.loads(r.read())
    secs = time.time() - t0

    text = d["choices"][0]["message"]["content"] or ""
    timings = d.get("timings") or {}
    usage = d.get("usage") or {}
    tokens = timings.get("predicted_n") or usage.get("completion_tokens") or 0
    tps = timings.get("predicted_per_second") or (tokens / secs if secs else 0)
    return text, secs, tokens, tps


CODE = re.compile(r"```(?:python)?\s*(.*?)```", re.S)


def extract(text):
    """The model's code, whether or not it remembered the fence."""
    m = CODE.search(text)
    return (m.group(1) if m else text).strip()


def check(code, test, entry, seconds=10):
    """Run the candidate against its test in a separate process. Passed, or why not."""
    prog = f"{code}\n\n{test}\n\ncheck({entry})\nprint('PASS')\n"
    with tempfile.TemporaryDirectory() as d:
        f = pathlib.Path(d) / "candidate.py"
        f.write_text(prog)
        try:
            p = subprocess.run([sys.executable, str(f)], capture_output=True,
                               text=True, timeout=seconds, cwd=d)
        except subprocess.TimeoutExpired:
            return False, "timed out"
    if p.returncode == 0 and "PASS" in p.stdout:
        return True, ""
    err = (p.stderr or p.stdout).strip().splitlines()
    return False, (err[-1][:120] if err else f"exit {p.returncode}")


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--label", required=True, help='what this configuration is, e.g. "laptop + 2 phones, 30B"')
    a.add_argument("--url", default="http://127.0.0.1:8080/v1")
    a.add_argument("--key", default=os.environ.get("BENCH_KEY", ""))
    a.add_argument("--model", default="mesh", help="ignored by llama.cpp, needed by cloud APIs")
    a.add_argument("--tasks", default=str(TASKS))
    a.add_argument("--limit", type=int, default=0, help="first N tasks only, for a quick check")
    a.add_argument("--timeout", type=int, default=900, help="seconds per request; a 30B split is slow")
    a.add_argument("--note", default="", help="conditions worth recording, e.g. 'USB tethering, phones charging'")
    args = a.parse_args()

    tasks = load_tasks(args.tasks, args.limit)
    RESULTS.mkdir(exist_ok=True)
    out = RESULTS / (re.sub(r"[^a-z0-9]+", "-", args.label.lower()).strip("-") + ".json")

    print(f"{args.label}: {len(tasks)} tasks against {args.url}")
    rows, passed = [], 0
    for i, t in enumerate(tasks, 1):
        try:
            text, secs, tokens, tps = ask(args.url, args.key, args.model, t["prompt"], args.timeout)
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            print(f"  {i:2}/{len(tasks)} {t['id']:<22} request failed: {e}")
            rows.append({"id": t["id"], "ok": False, "why": f"request failed: {e}",
                         "seconds": 0, "tokens": 0, "tps": 0})
            continue
        ok, why = check(extract(text), t["test"], t["entry_point"])
        passed += ok
        rows.append({"id": t["id"], "ok": ok, "why": why, "seconds": round(secs, 1),
                     "tokens": tokens, "tps": round(tps, 1)})
        print(f"  {i:2}/{len(tasks)} {t['id']:<22} {'pass' if ok else 'FAIL'}  "
              f"{secs:6.1f}s  {tps:5.1f} tok/s  {why}")

    done = [r for r in rows if r["tokens"]]
    summary = {
        "label": args.label,
        "note": args.note,
        "url": args.url,
        "when": time.strftime("%Y-%m-%d %H:%M"),
        "tasks": len(tasks),
        "passed": passed,
        "accuracy": round(100 * passed / len(tasks), 1) if tasks else 0,
        "median_tps": round(sorted(r["tps"] for r in done)[len(done) // 2], 1) if done else 0,
        "median_seconds": round(sorted(r["seconds"] for r in done)[len(done) // 2], 1) if done else 0,
        "total_seconds": round(sum(r["seconds"] for r in rows), 1),
        "results": rows,
    }
    out.write_text(json.dumps(summary, indent=2))
    print(f"\n{args.label}: {passed}/{len(tasks)} passed ({summary['accuracy']}%), "
          f"median {summary['median_tps']} tok/s, {summary['total_seconds']}s total")
    print(f"written to {out}")


if __name__ == "__main__":
    main()
