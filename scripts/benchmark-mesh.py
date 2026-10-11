#!/usr/bin/env python3
"""Repeatable streaming benchmark for a running MeshAI llama-server.

Example:
  adb forward tcp:18080 tcp:8080
  python3 scripts/benchmark-mesh.py --url http://127.0.0.1:18080 --runs 5

Each JSONL row includes client wall time/TTFT and llama.cpp's per-request timings
from the final streaming event. The prompt and decode settings are held constant.
"""

import argparse
import http.client
import json
import time
import statistics
from datetime import datetime, timezone
from urllib.parse import urlsplit


PROMPT = (
    "We are benchmarking local distributed inference on two Android phones. "
    "Explain how a model split between a host and a helper executes one generated token at a time. "
    "The host processes its assigned transformer layers, sends intermediate activations to the "
    "helper over RPC, receives the result, then computes the output token. Describe the difference "
    "between model weights transferred during setup and activations exchanged during inference. "
    "Keep the explanation precise, mention that layer placement and network latency affect speed, "
    "and do not claim the helper receives the original prompt text."
)


def run_once(url, run, max_tokens, timeout):
    parts = urlsplit(url)
    conn_cls = http.client.HTTPSConnection if parts.scheme == "https" else http.client.HTTPConnection
    conn = conn_cls(parts.hostname, parts.port, timeout=timeout)
    body = json.dumps({
        "model": "local",
        "messages": [{"role": "user", "content": PROMPT}],
        "temperature": 0,
        "seed": 1729,
        "max_tokens": max_tokens,
        "stream": True,
        "stream_options": {"include_usage": True},
    }).encode()
    prefix = (parts.path or "").rstrip("/")
    if prefix.endswith("/v1"):
        prefix = prefix[:-3]
    path = prefix + "/v1/chat/completions"
    start = time.perf_counter()
    first = None
    text_chars = 0
    timings = None
    usage = None
    error = None
    status = None
    try:
        conn.request("POST", path, body=body, headers={"Content-Type": "application/json"})
        response = conn.getresponse()
        status = response.status
        if status < 200 or status >= 300:
            error = response.read(4096).decode("utf-8", "replace")
        else:
            while True:
                line = response.readline()
                if not line:
                    break
                if not line.startswith(b"data: "):
                    continue
                data = line[6:].strip()
                if data == b"[DONE]":
                    break
                event = json.loads(data)
                if event.get("timings"):
                    timings = event["timings"]
                if event.get("usage"):
                    usage = event["usage"]
                for choice in event.get("choices", []):
                    delta = choice.get("delta", {}).get("content") or ""
                    if delta:
                        if first is None:
                            first = time.perf_counter()
                        text_chars += len(delta)
    except Exception as exc:  # report failures as benchmark data, do not hide them
        error = f"{type(exc).__name__}: {exc}"
    finally:
        conn.close()
    end = time.perf_counter()
    return {
        "run": run,
        "utc": datetime.now(timezone.utc).isoformat(),
        "http_status": status,
        "client_wall_s": round(end - start, 3),
        "client_ttft_s": round(first - start, 3) if first else None,
        "streamed_chars": text_chars,
        "usage": usage,
        "llama_timings": timings,
        "error": error,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:18080")
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--max-tokens", type=int, default=128)
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--delay", type=float, default=2)
    args = parser.parse_args()
    rows = []
    for n in range(1, args.runs + 1):
        row = run_once(args.url, n, args.max_tokens, args.timeout)
        rows.append(row)
        print(json.dumps(row, sort_keys=True), flush=True)
        if n < args.runs and args.delay > 0:
            time.sleep(args.delay)
    successful = [r for r in rows if r["http_status"] and not r["error"]]
    summary = {"type": "summary", "runs": len(rows), "successful": len(successful)}
    for key in ("client_wall_s", "client_ttft_s"):
        values = [r[key] for r in successful if r[key] is not None]
        if values:
            summary[key] = {
                "median": round(statistics.median(values), 3),
                "mean": round(statistics.fmean(values), 3),
                "min": round(min(values), 3),
                "max": round(max(values), 3),
            }
    for key in ("prompt_per_second", "predicted_per_second"):
        values = [r["llama_timings"].get(key) for r in successful if r["llama_timings"]]
        values = [v for v in values if isinstance(v, (int, float)) and v > 0]
        if values:
            summary[key] = {
                "median": round(statistics.median(values), 3),
                "mean": round(statistics.fmean(values), 3),
                "min": round(min(values), 3),
                "max": round(max(values), 3),
            }
    print(json.dumps(summary, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
