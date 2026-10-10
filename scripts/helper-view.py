"""The laptop's dashboard while a phone is the Host: the usual host.html, fed with live data, every control locked.
python3 view.py  -> http://localhost:8080/   (chat goes to the phone host's engine)"""
import json, subprocess, time, threading, urllib.request
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse

PAGE = "/home/prajwal/Documents/GitHub/maynards/agent/src/host.html"
HOST_IP = "10.155.241.73"
HOST, HELPER = "10BFBJ0SQJ001GG", "10BFAT1SA2000XP"
MODEL = "Qwen3-Coder-30B-A3B-Instruct-Q4_K_M.gguf"
SPLIT = [(HOST, 0, 14, True), ("laptop", 14, 35, False), (HELPER, 35, 48, False)]
LAYER_GB = 0.382
GB = 1e9
cache = {"phones": {}, "t0": time.time()}

def adb(s, cmd):
    try: return subprocess.run(["adb", "-s", s, "shell", cmd], capture_output=True, text=True, timeout=8).stdout
    except Exception: return ""

def phone(s):
    out = adb(s, "getprop ro.product.model; grep -E 'MemTotal|MemAvailable' /proc/meminfo; dumpsys battery | grep -E ' level:|AC powered|USB powered'")
    L = out.splitlines(); kb = lambda k: next((float(l.split()[1]) * 1024 for l in L if l.startswith(k)), 0)
    lvl = next((int(l.split(':')[1]) for l in L if 'level:' in l), -1)
    chg = any('powered: true' in l for l in L)
    return {"name": "Vivo " + (L[0].strip() if L else "phone"), "kind": "phone", "chip": "SM8850", "cores": 8, "maxGhz": 4.6,
            "totalBytes": kb("MemTotal"), "freeBytes": kb("MemAvailable"), "heat": -1.0, "battery": lvl, "charging": chg}

def poll():
    while True:
        for s in (HOST, HELPER): cache["phones"][s] = phone(s)
        try: code = urllib.request.urlopen(f"http://{HOST_IP}:8080/health", timeout=3).status
        except urllib.error.HTTPError as e: code = e.code
        except Exception: code = 0
        cache["health"] = code
        time.sleep(3)

def laptop():
    m = {l.split(':')[0]: float(l.split()[1]) * 1024 for l in open('/proc/meminfo')}
    return {"id": "laptop", "name": "81WE (this laptop · helper)", "kind": "laptop", "chip": "Intel Core i5-1035G1", "cores": 8,
            "maxGhz": 3.6, "totalBytes": m["MemTotal"], "freeBytes": m["MemAvailable"], "heat": -1.0, "battery": 100, "charging": True}

def state():
    ph = cache["phones"]; h, p2 = ph.get(HOST, {}), ph.get(HELPER, {})
    names = {HOST: (h.get("name", "phone") + " (host)"), "laptop": "81WE (this laptop)", HELPER: p2.get("name", "phone") + " ·00XP"}
    slices = [{"id": i, "name": names[i], "from": a, "to": b, "gb": round((b - a) * LAYER_GB + (0.43 if host else 0), 1), "host": host}
              for i, a, b, host in SPLIT]
    plan = {"verdict": "doable", "reason": "Needs 3 devices", "need_gb": 19.1, "slices": slices, "skipped": {}}
    code = cache.get("health", 0)
    status = "ready" if code == 200 else "loading" if code == 503 else "starting"
    peers = [{"id": s, "name": names[s], "addr": a, "usable_gb": u, "engine": "rpc" if s == HELPER else "host",
              "specs": dict(ph.get(s, {}), name=names[s]), "store": {"done": 13, "total": 13, "bytes": 5.1 * GB},
              "quiet_s": 0, "rtt_ms": r, "rtt_worst_ms": r * 3, "link": l}
             for s, a, u, r, l in ((HOST, HOST_IP, 8.0, 3.0, "usb"), (HELPER, "192.168.112.142", 7.2, 17.6, "wifi"))]
    return {"mesh": "1084e7f9", "invite": {"mesh": "1084e7f9", "hosts": [HOST_IP, "192.168.112.146"], "port": 7070, "code": "host is the phone"},
            "qr": "", "ctx": 4096, "pinned": None, "laptop": {"specs": laptop(), "usable_gb": 10.4}, "peers": peers,
            "models": [{"file": MODEL, "name": "Qwen3-Coder-30B-A3B-Instruct", "gb": 18.6, "layers": 48, "plan": plan}],
            "pool_gb": 25.6, "activity": [{"t": time.strftime("%H:%M:%S"), "line": f"phone host engine: {status}"}],
            "chats": [], "stats": {}, "online": True, "benching": False, "advice": [], "last_run": MODEL, "caps": {},
            "api": {"key": "", "urls": [f"http://{HOST_IP}:8080/v1"]},
            "run": {"status": status, "step": "ready" if status == "ready" else "loading layers on the phone host",
                    "model": "Qwen3-Coder-30B-A3B-Instruct", "plan": plan, "vision": False,
                    "seconds": int(time.time() - cache["t0"])}}

LOCK = b"""<style>#helper-lock{position:fixed;top:0;left:0;right:0;z-index:9999;background:#1d4ed8;color:#fff;font:600 13px system-ui;
text-align:center;padding:6px}body{padding-top:30px}</style><div id="helper-lock">Helper view: the phone is the host. Controls are locked.</div>"""

class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    def log_message(self, *a): pass
    def send(self, code, body, ctype="application/json"):
        self.send_response(code); self.send_header("content-type", ctype); self.send_header("cache-control", "no-store")
        self.send_header("content-length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        u = urlparse(self.path).path
        if u in ("/", "/index.html"):
            return self.send(200, open(PAGE, "rb").read().replace(b"<body>", b"<body>" + LOCK, 1)
                .replace(b"this laptop is the host", b"this laptop is a helper").replace(b">host${layersOf('laptop')", b">helper${layersOf('laptop')"), "text/html; charset=utf-8")
        if u == "/api/state": return self.send(200, json.dumps(state()).encode())
        if u.startswith(("/api/", "/svc/")): return self.send(200, b"[]" if u.endswith(("devices", "results", "tasks", "projects")) else b"{}")
        self.send(404, b"not found", "text/plain")
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("content-length") or 0))
        u = urlparse(self.path).path
        if u.startswith("/v1/"):   # chat: straight to the phone host's engine
            req = urllib.request.Request(f"http://{HOST_IP}:8080{u}", body, {"content-type": "application/json"})
            try: r = urllib.request.urlopen(req, timeout=600)
            except urllib.error.HTTPError as e: r = e
            self.send_response(r.status if hasattr(r, "status") else r.code)
            self.send_header("content-type", r.headers.get("content-type", "application/json"))
            self.send_header("transfer-encoding", "chunked"); self.end_headers()
            while True:
                c = r.read1(65536) if hasattr(r, "read1") else r.read(65536)
                if not c: break
                self.wfile.write(b"%x\r\n%s\r\n" % (len(c), c)); self.wfile.flush()
            self.wfile.write(b"0\r\n\r\n"); return
        self.send(403, json.dumps({"error": "locked: this laptop is a helper, the phone is the host"}).encode())

threading.Thread(target=poll, daemon=True).start()
time.sleep(4)
print("helper view on http://localhost:8080/")
ThreadingHTTPServer(("127.0.0.1", 8080), H).serve_forever()
