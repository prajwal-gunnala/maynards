#!/usr/bin/env python3
"""The laptop's dashboard (agent/src/host.html) while a phone is the Host, fed by what the phones' apps report.

Usage: scripts/helper-view.py HOST_SERIAL [HELPER_SERIAL ...] [--port 8080]   then open http://localhost:8080/

Every 2 s, over adb: each phone's files/status.json (written by the app: run, split, Helpers, Ned Capsules,
last answers, Spider Sense; never prompt or answer text), memory, battery, temperature, and bytes per second on
Wi-Fi / USB / VPN. The usual dashboard shows them; the "Phone mesh" card on Overview adds capsules, answers and
per-cable traffic. Read-only: every control is locked, and chat stays on the phone (its API needs the phone's key).
"""
import argparse
import json
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

PAGE = Path(__file__).resolve().parent.parent / "agent" / "src" / "host.html"
STATUS = "/sdcard/Android/data/ai.maynards.mesh/files/status.json"
IFACES = {"wlan0": "Wi-Fi", "rndis0": "USB", "ncm0": "USB", "usb0": "USB", "tun0": "VPN"}
RUN = {"READY": "ready", "LOADING": "loading", "STARTING": "starting", "FAILED": "failed", "IDLE": "idle"}
cache = {"phones": {}, "t0": time.time()}


def sh(serial, cmd):
    try:
        return subprocess.run(["adb", "-s", serial, "shell", cmd], capture_output=True, text=True, timeout=6).stdout
    except Exception:
        return ""


def poll(serials):
    last = {}
    while True:
        t = time.time()
        for s in serials:
            parts = sh(s, f"cat {STATUS}; echo '@@@'; cat /proc/net/dev; echo '@@@'; grep -E 'MemTotal|MemAvailable' /proc/meminfo;"
                          " dumpsys battery | grep -E ' level:| temperature:|AC powered|USB powered'").split("@@@")
            if len(parts) < 3:
                cache["phones"][s] = {"offline": True}
                continue
            try:
                status = json.loads(parts[0].strip() or "{}")
            except json.JSONDecodeError:
                status = cache["phones"].get(s, {}).get("status", {})
            counts = {}
            for line in parts[1].splitlines():
                if ":" in line and line.split(":")[0].strip() in IFACES:
                    name, data = line.split(":", 1)
                    f = data.split()
                    counts[name.strip()] = (int(f[0]), int(f[8]))
            rates = {}
            if s in last:
                dt = t - last[s][0]
                for k, (rx, tx) in counts.items():
                    if k in last[s][1]:
                        rates[f"{IFACES[k]} {k}"] = [round((rx - last[s][1][k][0]) / dt / 1024, 1), round((tx - last[s][1][k][1]) / dt / 1024, 1)]
            last[s] = (t, counts)
            v = {"charging": False}
            for line in parts[2].splitlines():
                b = line.replace(":", " ").split()
                if b[:1] == ["MemTotal"]: v["totalBytes"] = int(b[1]) * 1024
                if b[:1] == ["MemAvailable"]: v["freeBytes"] = int(b[1]) * 1024
                if b[:1] == ["level"]: v["battery"] = int(b[1])
                if b[:1] == ["temperature"]: v["temp_c"] = int(b[1]) / 10
                if len(b) >= 3 and b[1] == "powered" and b[2] == "true": v["charging"] = True
            cache["phones"][s] = {"status": status, "rates": rates, "vitals": v}
        time.sleep(max(0.0, 2 - (time.time() - t)))


def state():
    phones = cache["phones"]
    host = next((p["status"] for p in phones.values() if p.get("status", {}).get("role") == "host"), {})
    host_serial = next((s for s, p in phones.items() if p.get("status", {}).get("role") == "host"), None)
    split = host.get("split") or []
    slices = [{"id": x["name"], "name": x["name"], "from": x["from"], "to": x["to"] + 1, "gb": round(x["gb"], 1), "host": i == 0}
              for i, x in enumerate(split)]
    plan = {"verdict": "doable", "reason": f"Needs {len(slices)} device(s)" if slices else "", "slices": slices, "skipped": {}}
    status = RUN.get(host.get("status", "IDLE"), "idle")

    def specs(serial, name):
        p = phones.get(serial, {})
        v = p.get("vitals", {})
        return {"name": name, "kind": "phone", "totalBytes": v.get("totalBytes", 0), "freeBytes": v.get("freeBytes", 0),
                "battery": v.get("battery", -1), "charging": v.get("charging", False), "temp_c": v.get("temp_c"), "heat": -1}

    by_name = {p.get("status", {}).get("phone"): s for s, p in phones.items()}
    peers = []
    for p in host.get("peers", []):
        serial = next((s for s, ph in phones.items() if ph.get("status", {}).get("role") == "helper"), None)
        peers.append({"id": p["name"], "name": p["name"], "addr": p.get("addr"), "usable_gb": round(p.get("free_gb", 0), 1),
                      "engine": "rpc", "specs": specs(serial, p["name"]), "store": {}, "quiet_s": 0,
                      "rtt_ms": p.get("rtt_ms"), "rtt_worst_ms": p.get("rtt_ms"), "link": p.get("link"),
                      "rpc_event": "graph" if p.get("rpc_events") else "", "rpc_sequence": p.get("rpc_events", 0),
                      "rpc_event_age_s": p["rpc_age_ms"] / 1000 if p.get("rpc_age_ms", -1) >= 0 else None})
    activity = [{"t": time.strftime("%H:%M:%S", time.localtime(e["at"] / 1000)),
                 "line": f"{e['who']} · {e['text']}" + (f" ×{e['count']}" if e.get("kind") == "WORK" and e.get("count", 1) > 1 else "")}
                for e in reversed(host.get("sense", []))]
    links = {phones[s].get("status", {}).get("phone", s) + f" ({phones[s].get('status', {}).get('role', '?')})": phones[s].get("rates", {})
             for s in phones}
    answers = host.get("answers", [])
    model = host.get("model") or ""
    return {"mesh": "phone-hosted", "invite": {"mesh": "", "hosts": [], "port": 7070, "code": "the phone is the host"},
            "qr": "", "ctx": 4096, "pinned": None, "laptop": {"specs": specs(host_serial, (host.get("phone") or "phone") + " (host)"), "usable_gb": 0},
            "peers": peers, "models": [{"file": model, "name": model, "gb": round(sum(x["gb"] for x in split), 1), "layers": (split[-1]["to"] + 1) if split else 0, "plan": plan}] if model else [],
            "pool_gb": round(sum(x["gb"] for x in split), 1), "activity": activity, "chats": [],
            "stats": {"tps": answers[-1]["tps"]} if answers else {}, "online": True, "benching": False, "advice": [],
            "last_run": model, "caps": {}, "api": {"key": "", "urls": []},
            "run": {"status": status, "step": host.get("step", ""), "model": model, "plan": plan, "vision": False,
                    "seconds": int(time.time() - cache["t0"])},
            "phone_mesh": {"host": host.get("phone"), "threads": host.get("threads"), "threads_from_profile": host.get("threads_from_profile"),
                           "capsules": host.get("capsules", []), "answers": answers, "links": links,
                           "helpers": [p.get("status", {}) for p in phones.values() if p.get("status", {}).get("role") == "helper"]}}


LOCK = b"""<style>#helper-lock{position:fixed;top:0;left:0;right:0;z-index:9999;background:#1d4ed8;color:#fff;font:600 13px system-ui;
text-align:center;padding:6px}body{padding-top:30px}</style><div id="helper-lock">Laptop view: a phone is the Host. Read-only.</div>"""


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def send(self, code, body, ctype="application/json"):
        self.send_response(code); self.send_header("content-type", ctype); self.send_header("cache-control", "no-store")
        self.send_header("content-length", str(len(body))); self.end_headers(); self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path).path
        if u in ("/", "/index.html"):
            return self.send(200, PAGE.read_bytes().replace(b"<body>", b"<body>" + LOCK, 1), "text/html; charset=utf-8")
        if u == "/api/state":
            return self.send(200, json.dumps(state()).encode())
        if u.startswith(("/api/", "/svc/")):
            return self.send(200, b"[]" if u.endswith(("devices", "results", "tasks", "projects")) else b"{}")
        self.send(404, b"not found", "text/plain")

    def do_POST(self):
        self.rfile.read(int(self.headers.get("content-length") or 0))
        self.send(403, json.dumps({"error": "read-only: the phone is the Host; use the phone to run or chat"}).encode())


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("serials", nargs="+")
    ap.add_argument("--port", type=int, default=8080)
    a = ap.parse_args()
    threading.Thread(target=poll, args=(a.serials,), daemon=True).start()
    time.sleep(3)
    print(f"laptop view on http://localhost:{a.port}/ for {', '.join(a.serials)}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", a.port), H).serve_forever()


if __name__ == "__main__":
    main()
