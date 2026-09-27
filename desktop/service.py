"""The desktop app's local service: runs coding tasks with Aider against the mesh and records every step.

It listens on 127.0.0.1 only, and every call must carry the session token the window was given, so no web page in
a browser can make it run anything. A task is one Aider run (`--message`) in a project's git repo; its output is
turned into events (text, edit, commit, tokens), then the project's test command is run by us, so the pass/fail
count comes from the tests themselves and not from what the model says. Aider may not run shell commands of its own
(--no-suggest-shell-commands); every change it makes is a git commit, and Undo is a `git revert` of it.

Stored under ~/.config/meshai/agent/: projects.json, and tasks/<id>.json + tasks/<id>.jsonl (the events).
"""
import json
import os
import re
import secrets
import signal
import subprocess
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
AIDER = os.path.join(HERE, "aider", "run-aider.sh")
UI = os.path.join(HERE, "ui")
HOST = os.environ.get("MESH_HOST_URL", "http://localhost:8080")
HOME = os.path.expanduser("~/.config/meshai/agent")
TASKS = os.path.join(HOME, "tasks")

EDIT = re.compile(r"^Applied edit to (.+)$")
COMMIT = re.compile(r"^Commit ([0-9a-f]{7,40}) (.+)$")
TOKENS = re.compile(r"^Tokens: ([\d.]+k?) sent, ([\d.]+k?) received")
PASSED = re.compile(r"(\d+) passed")
FAILED = re.compile(r"(\d+) (?:failed|error)")


def now():
    return time.time()


def git(project, *args, timeout=20):
    r = subprocess.run(["git", *args], cwd=project, capture_output=True, text=True, timeout=timeout)
    return r.returncode, r.stdout, r.stderr


class Task:
    def __init__(self, svc, meta):
        self.svc, self.meta = svc, meta
        self.events = []
        self.lock = threading.Lock()
        self.proc = None
        self.path = os.path.join(TASKS, meta["id"])

    # -------------------------------------------------- events
    def emit(self, kind, **kw):
        ev = {"t": kind, "at": now(), **kw}
        with self.lock:
            self.events.append(ev)
            with open(self.path + ".jsonl", "a") as f:
                f.write(json.dumps(ev) + "\n")
        return ev

    def save(self):
        with open(self.path + ".json", "w") as f:
            json.dump(self.meta, f)

    # -------------------------------------------------- the run
    def run(self):
        m = self.meta
        self.emit("start", prompt=m["prompt"], project=m["project"], test_cmd=m["test_cmd"], perms=m["perms"])
        mesh = self.svc.mesh() or {}
        run = mesh.get("run") or {}
        m["model"] = run.get("model") or ""
        m["devices"] = len(mesh.get("peers") or []) + 1
        if run.get("status") != "ready":
            return self.finish("failed", "No model is running on the mesh: press Run first")
        perms = m["perms"]
        argv = [AIDER, m["test_cmd"] if perms["tests"] else "-", "--message", m["prompt"], "--yes-always",
                "--no-pretty", "--no-fancy-input", "--no-auto-lint"]
        if not perms["edit"]:
            argv.append("--dry-run")                                # shows the change, writes nothing
        if not perms["commit"]:
            argv += ["--no-auto-commits", "--no-dirty-commits"]     # changes stay in the working tree
        if not perms["shell"]:
            argv.append("--no-suggest-shell-commands")               # with --yes-always a suggestion would run
        env = dict(os.environ, TERM="dumb", NO_COLOR="1", PYTHONUNBUFFERED="1")
        try:
            self.proc = subprocess.Popen(argv, cwd=m["project"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                         stdin=subprocess.DEVNULL, env=env, start_new_session=True)
        except OSError as e:
            return self.finish("failed", f"Aider did not start: {e}")
        self.read_output()
        code = self.proc.wait()
        if m.get("status") == "stopped":
            return
        if perms["tests"]:
            self.run_tests()
        self.finish("done" if code == 0 else "failed", "" if code == 0 else f"Aider exited with {code}")

    def read_output(self):
        """Stream Aider's output as text events, flushed every 0.3 s, and pick out edits, commits and tokens."""
        buf, line, last = b"", "", now()
        fd = self.proc.stdout.fileno()
        while True:
            chunk = os.read(fd, 4096)
            if chunk:
                buf += chunk
            if buf and (not chunk or now() - last > 0.3 or len(buf) > 2000):
                text = buf.decode("utf-8", "replace").replace("\r", "")
                buf, last = b"", now()
                self.emit("text", text=text)
                line += text
                *done, line = line.split("\n")
                for ln in done:
                    self.parse(ln.strip())
            if not chunk:
                break
        if line.strip():
            self.parse(line.strip())

    def parse(self, ln):
        if (mt := EDIT.match(ln)):
            self.emit("edit", file=mt.group(1))
            self.meta.setdefault("files", [])
            if mt.group(1) not in self.meta["files"]:
                self.meta["files"].append(mt.group(1))
        elif (mt := COMMIT.match(ln)):
            self.emit("commit", hash=mt.group(1), message=mt.group(2))
            self.meta.setdefault("commits", []).append(mt.group(1))
        elif (mt := TOKENS.match(ln)):
            self.emit("tokens", sent=mt.group(1), received=mt.group(2))

    def run_tests(self):
        m = self.meta
        self.emit("tests_start", cmd=m["test_cmd"])
        t0 = now()
        try:
            r = subprocess.run(m["test_cmd"], shell=True, cwd=m["project"], capture_output=True, text=True, timeout=600)
            out, ok = (r.stdout + r.stderr)[-6000:], r.returncode == 0
        except subprocess.TimeoutExpired:
            out, ok = "tests took longer than 10 minutes", False
        tail = out.strip().splitlines()[-1] if out.strip() else ""
        passed = int(PASSED.search(tail).group(1)) if PASSED.search(tail) else None
        failed = int(FAILED.search(tail).group(1)) if FAILED.search(tail) else (0 if ok and passed is not None else None)
        m["tests"] = {"ok": ok, "passed": passed, "failed": failed, "summary": tail}
        self.emit("tests", ok=ok, passed=passed, failed=failed, summary=tail, output=out, seconds=round(now() - t0, 1))

    def finish(self, status, why=""):
        m = self.meta
        m["status"], m["ended"] = status, now()
        mesh = self.svc.mesh() or {}
        tps = next((c.get("tps") for c in reversed(mesh.get("chats") or []) if c.get("tps")), None)
        m["tps"] = tps
        if why:
            self.emit("error", text=why)
        self.emit("done", status=status, seconds=round(m["ended"] - m["started"], 1), tps=tps)
        self.save()

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.meta["status"] = "stopped"
            try:
                os.killpg(self.proc.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            self.finish("stopped", "Stopped")


class Service:
    def __init__(self):
        os.makedirs(TASKS, exist_ok=True)
        self.token = secrets.token_hex(16)
        self.tasks = {}
        self.lock = threading.Lock()
        self.load()

    # -------------------------------------------------- storage
    def load(self):
        for f in sorted(os.listdir(TASKS)):
            if f.endswith(".json"):
                try:
                    meta = json.load(open(os.path.join(TASKS, f)))
                except (OSError, ValueError):
                    continue
                t = Task(self, meta)
                try:
                    t.events = [json.loads(line) for line in open(t.path + ".jsonl")]
                except OSError:
                    pass
                if meta.get("status") == "running":      # the app closed mid-task
                    meta["status"] = "stopped"
                self.tasks[meta["id"]] = t

    def projects(self):
        try:
            return json.load(open(os.path.join(HOME, "projects.json")))
        except (OSError, ValueError):
            return []

    def add_project(self, path):
        path = os.path.abspath(os.path.expanduser(path))
        if not os.path.isdir(os.path.join(path, ".git")):
            raise ValueError("not a git repository")
        ps = [p for p in self.projects() if p["path"] != path]
        ps.insert(0, {"path": path, "name": os.path.basename(path), "test_cmd": guess_tests(path)})
        json.dump(ps, open(os.path.join(HOME, "projects.json"), "w"))
        return ps

    # -------------------------------------------------- tasks
    def start(self, project, prompt, test_cmd, perms=None):
        if not prompt.strip():
            raise ValueError("empty task")
        if not os.path.isdir(os.path.join(project, ".git")):
            raise ValueError("not a git repository")
        with self.lock:
            if any(t.meta["status"] == "running" for t in self.tasks.values()):
                raise ValueError("a task is already running: the mesh answers one at a time")
            tid = time.strftime("%Y%m%d-%H%M%S-") + secrets.token_hex(2)
            meta = {"id": tid, "project": project, "prompt": prompt.strip(), "test_cmd": test_cmd or guess_tests(project),
                    "status": "running", "started": now(), "title": prompt.strip().splitlines()[0][:80],
                    "perms": {k: bool((perms or {}).get(k, v)) for k, v in DEFAULT_PERMS.items()}}
            t = Task(self, meta)
            t.save()
            self.tasks[tid] = t
        threading.Thread(target=self.guard, args=(t,), daemon=True).start()
        return tid

    def guard(self, t):
        try:
            t.run()
        except Exception as e:              # a bug here must end the task, not leave it "running" forever
            t.finish("failed", f"{e.__class__.__name__}: {e}")

    def undo(self, t, commit):
        if not t.meta.get("perms", {}).get("commit", True):
            raise ValueError("this task did not commit")
        if commit not in t.meta.get("commits", []):
            raise ValueError("not a commit of this task")
        code, out, err = git(t.meta["project"], "revert", "--no-edit", commit)
        if code != 0:
            raise ValueError((err or out).strip()[-300:])
        t.emit("undo", hash=commit)
        t.meta.setdefault("undone", []).append(commit)
        t.save()

    def mesh(self):
        try:
            with urllib.request.urlopen(HOST + "/api/state", timeout=3) as r:
                return json.loads(r.read())
        except Exception:
            return None


# What the agent may do. Shell stays off unless the owner turns it on: with --yes-always, Aider would run any
# command it suggests without asking.
DEFAULT_PERMS = {"edit": True, "commit": True, "tests": True, "shell": False}


def guess_tests(path):
    if os.path.exists(os.path.join(path, "Cargo.toml")):
        return "cargo test -q"
    if os.path.exists(os.path.join(path, "package.json")):
        return "npm test --silent"
    return "python3 -m pytest -q"


# ---------------------------------------------------------------- HTTP

def make_handler(svc):
    class H(BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def send(self, code, body, ctype="application/json"):
            data = body if isinstance(body, bytes) else json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def static(self):
            name = self.path.split("?")[0].lstrip("/") or "index.html"
            f = os.path.normpath(os.path.join(UI, name))
            if not f.startswith(UI) or not os.path.isfile(f):
                return self.send(404, {"error": "not found"})
            ctype = {"html": "text/html; charset=utf-8", "css": "text/css", "js": "text/javascript",
                     "svg": "image/svg+xml"}.get(f.rsplit(".", 1)[-1], "application/octet-stream")
            self.send(200, open(f, "rb").read(), ctype)

        def authed(self):
            return secrets.compare_digest(self.headers.get("X-Mesh-Token", ""), svc.token)

        def do_GET(self):
            if not self.path.startswith("/svc/"):
                return self.static()
            if not self.authed():
                return self.send(403, {"error": "forbidden"})
            try:
                self.send(200, self.get(self.path))
            except (ValueError, KeyError) as e:
                self.send(400, {"error": str(e)})

        def do_POST(self):
            if not self.authed():
                return self.send(403, {"error": "forbidden"})
            try:
                n = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(n) or b"{}")
                self.send(200, self.post(self.path, body))
            except (ValueError, KeyError) as e:
                self.send(400, {"error": str(e)})

        def get(self, path):
            route, _, query = path.partition("?")
            q = dict(p.split("=", 1) for p in query.split("&") if "=" in p)
            q = {k: urllib.request.unquote(v) for k, v in q.items()}
            if route == "/svc/projects":
                return svc.projects()
            if route == "/svc/tasks":
                ts = [t.meta for t in svc.tasks.values() if not q.get("project") or t.meta["project"] == q["project"]]
                return sorted(ts, key=lambda m: m["started"], reverse=True)[:100]
            if route.startswith("/svc/tasks/"):
                t = svc.tasks[route.rsplit("/", 1)[1]]
                after = int(q.get("after", 0))
                with t.lock:
                    return {"meta": t.meta, "events": t.events[after:], "next": len(t.events)}
            if route == "/svc/diff":
                code, out, err = git(q["project"], "show", "--stat", "--patch", "--format=%h %s", q["commit"])
                return {"diff": out[:200000] if code == 0 else err}
            if route == "/svc/mesh":
                return svc.mesh() or {"offline": True}
            raise KeyError(route)

        def post(self, path, b):
            if path == "/svc/projects":
                return svc.add_project(b["path"])
            if path == "/svc/tasks":
                return {"id": svc.start(b["project"], b.get("prompt", ""), b.get("test_cmd", ""), b.get("perms"))}
            if path.startswith("/svc/tasks/") and path.endswith("/stop"):
                svc.tasks[path.split("/")[3]].stop()
                return {"ok": True}
            if path.startswith("/svc/tasks/") and path.endswith("/undo"):
                svc.undo(svc.tasks[path.split("/")[3]], b["commit"])
                return {"ok": True}
            if path in ("/svc/run", "/svc/stop", "/svc/pin", "/svc/unpin", "/svc/newqr"):   # the mesh's own, passed through
                req = urllib.request.Request(HOST + path.replace("/svc", "/api"), data=json.dumps(b).encode(), method="POST")
                return json.loads(urllib.request.urlopen(req, timeout=10).read() or b"{}")
            raise KeyError(path)
    return H


def serve(svc, port=0):
    server = ThreadingHTTPServer(("127.0.0.1", port), make_handler(svc))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server.server_address[1]


if __name__ == "__main__":         # run alone for testing: prints the URL with its token
    s = Service()
    p = serve(s, int(os.environ.get("PORT", "0")))
    print(f"http://127.0.0.1:{p}/  token {s.token}", flush=True)
    threading.Event().wait()
