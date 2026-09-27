#!/usr/bin/env python3
"""MeshAI for Linux: the laptop's window onto the mesh.

Left: the devices, the model and its split, Run and Stop, stats. Right: a real terminal running Aider
against the model the mesh serves, plus a shell and the engine log. It is a front end: the brain is still
`mesh host` (agent/src/host.rs), which this window starts if it is not already running and talks to over
its local API. Closing the window leaves the host and the phones as they are.
"""
import json
import os
import subprocess
import threading
import time
import urllib.request

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
gi.require_version("GdkPixbuf", "2.0")
gi.require_version("Vte", "2.91")
from gi.repository import Gdk, GdkPixbuf, GLib, Gtk, Pango, Vte  # noqa: E402

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HOST = os.environ.get("MESH_HOST_URL", "http://localhost:8080")
AIDER = os.path.join(REPO, "desktop", "aider", "run-aider.sh")
DEMO = os.path.expanduser("~/meshai-aider-demo")
CTX_FOR_AIDER = 16384

CSS = b"""
window, .side { background: #0b0b0d; color: #ededed; }
.side { border-right: 1px solid #26262a; }
.card { background: #131316; border: 1px solid #26262a; border-radius: 8px; padding: 12px; }
.h { color: #8b8b93; font-size: 11px; letter-spacing: 1px; }
.big { font-size: 22px; font-weight: 600; }
.mono { font-family: monospace; font-size: 12px; color: #b8b8c0; }
.ok { color: #4ade80; } .warn { color: #fbbf24; } .bad { color: #f87171; } .helper { color: #a78bfa; }
.feed { font-family: monospace; font-size: 11px; color: #8b8b93; }
button.primary { background: #4ade80; color: #09090b; font-weight: 600; }
notebook tab { padding: 6px 14px; }
"""


# ---------------------------------------------------------------- host API

def api(path, body=None, timeout=5):
    req = urllib.request.Request(HOST + path, data=None if body is None else json.dumps(body).encode(),
                                 method="GET" if body is None else "POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read() or b"{}")


def ensure_host():
    """Start `mesh host` if nothing answers on the API. It keeps running after this window closes."""
    try:
        api("/api/state")
        return "running"
    except Exception:
        pass
    env = dict(os.environ, MESH_CTX=str(CTX_FOR_AIDER),
               MESH_MODELS=os.environ.get("MESH_MODELS", os.path.expanduser("~/meshai-models")))
    log = open("/tmp/mesh-host.log", "a")
    subprocess.Popen([os.path.join(REPO, "agent", "target", "release", "mesh"), "host", "--port", "8080"],
                     cwd=REPO, env=env, stdout=log, stderr=log, start_new_session=True)
    for _ in range(20):
        time.sleep(0.5)
        try:
            api("/api/state")
            return "started"
        except Exception:
            continue
    return "failed"


# ---------------------------------------------------------------- small widgets

def label(text="", css=(), xalign=0.0, wrap=False, select=False):
    lb = Gtk.Label(label=text, xalign=xalign)
    lb.set_line_wrap(wrap)
    lb.set_selectable(select)
    for c in css:
        lb.get_style_context().add_class(c)
    return lb


def card(title):
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
    box.get_style_context().add_class("card")
    if title:
        box.pack_start(label(title.upper(), ["h"]), False, False, 0)
    return box


def set_class(widget, cls, all_=("ok", "warn", "bad", "helper")):
    ctx = widget.get_style_context()
    for c in all_:
        ctx.remove_class(c)
    if cls:
        ctx.add_class(cls)


def terminal():
    t = Vte.Terminal()
    t.set_font(Pango.FontDescription("Monospace 11"))
    t.set_scrollback_lines(10000)
    t.set_color_background(Gdk.RGBA(0.04, 0.04, 0.05, 1))
    t.set_color_foreground(Gdk.RGBA(0.93, 0.93, 0.93, 1))
    return t


def spawn(term, argv, cwd, env=None):
    envv = [f"{k}={v}" for k, v in (env or os.environ).items()]
    term.spawn_async(Vte.PtyFlags.DEFAULT, cwd, argv, envv, GLib.SpawnFlags.SEARCH_PATH,
                     None, None, -1, None, None, None)


# ---------------------------------------------------------------- the window

class MeshWindow(Gtk.Window):
    def __init__(self, host_note):
        super().__init__(title="MeshAI")
        self.set_default_size(1440, 900)
        self.state = {}
        self.models_seen = []
        self.aider_running = False

        bar = Gtk.HeaderBar(title="MeshAI", subtitle=host_note)
        bar.set_show_close_button(True)
        self.set_titlebar(bar)
        self.bar = bar

        paned = Gtk.Paned(orientation=Gtk.Orientation.HORIZONTAL)
        self.add(paned)
        paned.pack1(self.build_side(), False, False)
        paned.pack2(self.build_work(), True, False)
        paned.set_position(420)

        threading.Thread(target=self.poll, daemon=True).start()

    # ------------------------------------------------ left: devices, model, stats
    def build_side(self):
        scroll = Gtk.ScrolledWindow()
        scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        side = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12, margin=14)
        scroll.get_style_context().add_class("side")
        scroll.add(side)

        # devices
        dev = card("Devices")
        self.devices = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        dev.pack_start(self.devices, False, False, 0)
        pair = Gtk.Button(label="Pair a phone")
        pair.connect("clicked", self.on_pair)
        dev.pack_start(pair, False, False, 0)
        side.pack_start(dev, False, False, 0)

        # model
        mod = card("Model")
        self.model_pick = Gtk.ComboBoxText()
        self.model_pick.connect("changed", lambda *_: self.render_plan())
        mod.pack_start(self.model_pick, False, False, 0)
        self.verdict = label("", ["big"])
        mod.pack_start(self.verdict, False, False, 0)
        self.plan_text = label("", ["mono"], wrap=True)
        mod.pack_start(self.plan_text, False, False, 0)
        row = Gtk.Box(spacing=8)
        self.run_btn = Gtk.Button(label="Run")
        self.run_btn.get_style_context().add_class("primary")
        self.run_btn.connect("clicked", self.on_run)
        stop = Gtk.Button(label="Stop")
        stop.connect("clicked", lambda *_: self.post("/api/stop"))
        row.pack_start(self.run_btn, True, True, 0)
        row.pack_start(stop, True, True, 0)
        mod.pack_start(row, False, False, 0)
        self.run_status = label("", ["mono"], wrap=True)
        mod.pack_start(self.run_status, False, False, 0)
        self.progress = Gtk.ProgressBar()
        mod.pack_start(self.progress, False, False, 0)
        self.ctx_note = label("", ["mono", "warn"], wrap=True)
        mod.pack_start(self.ctx_note, False, False, 0)
        side.pack_start(mod, False, False, 0)

        # stats
        st = card("Stats")
        grid = Gtk.Grid(column_spacing=18, row_spacing=4)
        self.stat = {}
        for i, (k, name) in enumerate([("last", "Last tok/s"), ("first", "First word"), ("avg", "Avg tok/s"),
                                       ("answers", "Answers")]):
            grid.attach(label(name, ["h"]), i % 2, (i // 2) * 2, 1, 1)
            self.stat[k] = label("–", ["big"])
            grid.attach(self.stat[k], i % 2, (i // 2) * 2 + 1, 1, 1)
        st.pack_start(grid, False, False, 0)
        side.pack_start(st, False, False, 0)

        # activity
        act = card("Activity")
        self.feed = label("", ["feed"], wrap=True, select=True)
        act.pack_start(self.feed, False, False, 0)
        side.pack_start(act, False, False, 0)
        return scroll

    # ------------------------------------------------ right: Aider, shell, log
    def build_work(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        tools = Gtk.Box(spacing=8, margin=10)
        tools.pack_start(label("Project", ["h"]), False, False, 0)
        self.folder = Gtk.FileChooserButton(title="Project folder", action=Gtk.FileChooserAction.SELECT_FOLDER)
        if os.path.isdir(DEMO):
            self.folder.set_filename(DEMO)
        tools.pack_start(self.folder, False, False, 0)
        tools.pack_start(label("Tests", ["h"]), False, False, 0)
        self.test_cmd = Gtk.Entry(text="python3 -m pytest -q", width_chars=26)
        tools.pack_start(self.test_cmd, False, False, 0)
        start = Gtk.Button(label="Start Aider")
        start.get_style_context().add_class("primary")
        start.connect("clicked", self.on_aider)
        tools.pack_start(start, False, False, 0)
        self.aider_note = label("", ["mono"])
        tools.pack_start(self.aider_note, True, True, 0)
        box.pack_start(tools, False, False, 0)

        self.tabs = Gtk.Notebook()
        self.aider_term = terminal()
        self.aider_term.connect("child-exited", self.on_aider_exit)
        self.aider_term.feed(b"\r\n  Pick a project folder (a git repo) and press Start Aider.\r\n"
                             b"  Aider edits the code with the model the mesh runs, then runs the tests.\r\n")
        self.tabs.append_page(self.wrap(self.aider_term), Gtk.Label(label="Aider"))
        self.shell_term = terminal()
        spawn(self.shell_term, [os.environ.get("SHELL", "/bin/bash")], self.project())
        self.tabs.append_page(self.wrap(self.shell_term), Gtk.Label(label="Shell"))
        self.log_term = terminal()
        spawn(self.log_term, ["tail", "-n", "40", "-F", "/tmp/mesh-host.log", "/tmp/mesh-host-engine.log"], REPO)
        self.tabs.append_page(self.wrap(self.log_term), Gtk.Label(label="Log"))
        box.pack_start(self.tabs, True, True, 0)
        return box

    @staticmethod
    def wrap(term):
        sw = Gtk.ScrolledWindow()
        sw.add(term)
        return sw

    def project(self):
        return self.folder.get_filename() or (DEMO if os.path.isdir(DEMO) else os.path.expanduser("~"))

    # ------------------------------------------------ actions
    def post(self, path, body=None):
        def go():
            try:
                api(path, body or {}, timeout=10)
            except Exception as e:
                GLib.idle_add(self.run_status.set_text, f"{path}: {e}")
        threading.Thread(target=go, daemon=True).start()

    def on_run(self, *_):
        i = self.model_pick.get_active()
        if 0 <= i < len(self.models_seen):
            self.post("/api/run", {"model": self.models_seen[i]["file"]})

    def on_aider(self, *_):
        folder = self.project()
        if not os.path.isdir(os.path.join(folder, ".git")):
            self.aider_note.set_text("Not a git repo: Aider needs one")
            set_class(self.aider_note, "bad")
            return
        if (self.state.get("run") or {}).get("status") != "ready":
            self.aider_note.set_text("No model running yet: press Run first")
            set_class(self.aider_note, "warn")
            return
        if self.aider_running:
            self.aider_note.set_text("Aider is already running")
            return
        self.aider_term.reset(True, True)
        self.tabs.set_current_page(0)
        spawn(self.aider_term, [AIDER, self.test_cmd.get_text() or "pytest -q"], folder)
        self.aider_running = True
        self.aider_note.set_text(f"Aider in {folder}")
        set_class(self.aider_note, "ok")
        self.aider_term.grab_focus()

    def on_aider_exit(self, *_):
        self.aider_running = False
        self.aider_note.set_text("Aider stopped")
        set_class(self.aider_note, None)

    def on_pair(self, *_):
        s = self.state
        dlg = Gtk.Dialog(title="Pair a phone", transient_for=self, modal=True)
        dlg.add_button("New code", 1)
        dlg.add_button("Close", Gtk.ResponseType.CLOSE)
        area = dlg.get_content_area()
        area.set_spacing(10)
        area.set_border_width(16)
        area.pack_start(label("On the phone: MeshAI → Helper → Scan to join", xalign=0.5), False, False, 0)
        img = Gtk.Image()
        try:
            loader = GdkPixbuf.PixbufLoader.new_with_type("svg")
            loader.write(s.get("qr", "").encode())
            loader.close()
            img.set_from_pixbuf(loader.get_pixbuf().scale_simple(320, 320, GdkPixbuf.InterpType.NEAREST))
        except Exception:
            img = label("QR not available: is the host running?")
        area.pack_start(img, False, False, 0)
        hosts = (s.get("invite") or {}).get("hosts") or []
        area.pack_start(label("Laptop: " + " · ".join(hosts), ["mono"], xalign=0.5), False, False, 0)
        area.pack_start(label("Turn on USB tethering first, so the phone joins over the cable",
                              ["mono", "warn"], xalign=0.5), False, False, 0)
        dlg.show_all()
        if dlg.run() == 1:
            self.post("/api/newqr")
        dlg.destroy()

    # ------------------------------------------------ polling and drawing
    def poll(self):
        while True:
            try:
                s = api("/api/state")
                GLib.idle_add(self.render, s)
            except Exception as e:
                GLib.idle_add(self.bar.set_subtitle, f"host not answering ({e.__class__.__name__}), retrying")
            time.sleep(2)

    def render(self, s):
        self.state = s
        run = s.get("run") or {}
        tps = next((c.get("tps") for c in reversed(s.get("chats", [])) if c.get("tps")), None)
        self.bar.set_subtitle(" · ".join(x for x in [
            run.get("model") or "no model", run.get("status", ""),
            f"{tps:.1f} tok/s" if tps else "", (lambda n: f"{n} device" + ("s" if n > 1 else ""))(len(s.get("peers") or []) + 1)] if x))
        self.render_devices(s)
        self.render_models(s)
        self.render_run(s, run)
        self.render_stats(s)
        self.feed.set_text("\n".join(f"{a['t']}  {a['line']}" for a in s.get("activity", [])[:10]))
        return False

    def render_devices(self, s):
        for c in self.devices.get_children():
            self.devices.remove(c)
        slices = ((s.get("run") or {}).get("plan") or {}).get("slices") or []
        held = {x.get("id"): f"layers {x['from']}–{x['to'] - 1}" for x in slices if x.get("to", 0) > x.get("from", 0)}
        lap = s.get("laptop") or {}
        rows = [("💻", (lap.get("specs") or {}).get("name", "This laptop"), "host",
                 f"{lap.get('usable_gb', 0):.1f} GB for models", held.get("laptop", ""), "ok")]
        for p in s.get("peers", []):
            rtt = p.get("rtt_ms")
            link = {"USB cable": "USB"}.get(p.get("link"), p.get("link") or "link")
            st = p.get("store") or {}
            stored = f"stored {st.get('done')}/{st.get('total')}" if st.get("total") else ""
            cls = "ok" if rtt is not None and rtt <= 10 else "warn" if rtt is not None and rtt <= 60 else "bad"
            rows.append(("📱", p.get("name", "phone"), f"{link} {rtt:.0f} ms" if rtt is not None else link,
                         f"{p.get('usable_gb', 0):.1f} GB for models", " · ".join(x for x in [held.get(p.get("id"), ""), stored] if x), cls))
        for icon, name, link, mem, extra, cls in rows:
            b = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
            top = Gtk.Box(spacing=8)
            top.pack_start(label(f"{icon}  {name}"), False, False, 0)
            lk = label(link, ["mono", cls])
            top.pack_end(lk, False, False, 0)
            b.pack_start(top, False, False, 0)
            b.pack_start(label(" · ".join(x for x in [mem, extra] if x), ["mono"]), False, False, 0)
            self.devices.pack_start(b, False, False, 0)
        if not s.get("peers"):
            self.devices.pack_start(label("No phones yet: Pair a phone", ["mono", "warn"]), False, False, 0)
        self.devices.show_all()

    def render_models(self, s):
        models = s.get("models", [])
        if [m["file"] for m in models] != [m["file"] for m in self.models_seen]:
            keep = self.model_pick.get_active()
            self.model_pick.remove_all()
            for m in models:
                self.model_pick.append_text(f"{m['name']}  ·  {m['gb']:.1f} GB")
            coder = next((i for i, m in enumerate(models) if "Coder" in m["file"]), 0)
            self.model_pick.set_active(keep if keep >= 0 and self.models_seen else coder)
        self.models_seen = models
        self.render_plan()

    def render_plan(self):
        i = self.model_pick.get_active()
        if not (0 <= i < len(self.models_seen)):
            return
        p = self.models_seen[i].get("plan") or {}
        v = p.get("verdict", "")
        self.verdict.set_text({"doable": "Doable", "tight": "Tight", "not_possible": "Not possible"}.get(v, v))
        set_class(self.verdict, {"doable": "ok", "tight": "warn", "not_possible": "bad"}.get(v))
        lines = [f"{p.get('reason', '')} · needs {p.get('need_gb', 0):.1f} GB"]
        for x in p.get("slices") or []:
            lines.append(f"{'💻' if x.get('host') else '📱'} {x['name'].replace(' (this laptop)', '')}: "
                         f"layers {x['from']}–{x['to'] - 1} · {x['gb']:.1f} GB")
        for name, why in (p.get("skipped") or {}).items():
            lines.append(f"skipped {name}: {why}")
        self.plan_text.set_text("\n".join(lines))
        self.run_btn.set_sensitive(v != "not_possible")

    def render_run(self, s, run):
        status = run.get("status", "idle")
        secs = run.get("seconds")
        self.run_status.set_text(f"{status} · {run.get('step', '')}" + (f" · {secs} s" if secs and status != "ready" else ""))
        set_class(self.run_status, {"ready": "ok", "failed": "bad", "starting": "warn", "loading": "warn"}.get(status))
        if status in ("starting", "loading"):
            self.progress.pulse()
        else:
            self.progress.set_fraction(1.0 if status == "ready" else 0.0)
        ctx = s.get("ctx")
        self.ctx_note.set_text("" if not ctx or ctx >= CTX_FOR_AIDER else
                               f"Host context is {ctx} tokens: Aider needs {CTX_FOR_AIDER}. Restart the host with MESH_CTX={CTX_FOR_AIDER}.")

    def render_stats(self, s):
        st = s.get("stats") or {}
        last = next((c for c in reversed(s.get("chats", [])) if c.get("tps")), {})
        self.stat["last"].set_text(f"{last['tps']:.1f}" if last.get("tps") else "–")
        self.stat["first"].set_text(f"{last['first_ms'] / 1000:.1f} s" if last.get("first_ms") else "–")
        self.stat["avg"].set_text(f"{st['avg_tps']:.1f}" if st.get("avg_tps") else "–")
        self.stat["answers"].set_text(str(st.get("answers", 0)))


def main():
    style = Gtk.CssProvider()
    style.load_from_data(CSS)
    Gtk.StyleContext.add_provider_for_screen(Gdk.Screen.get_default(), style, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
    Gtk.Settings.get_default().set_property("gtk-application-prefer-dark-theme", True)
    note = {"running": "host running", "started": "host started", "failed": "host failed to start: see /tmp/mesh-host.log"}[ensure_host()]
    win = MeshWindow(note)
    win.connect("destroy", Gtk.main_quit)
    win.show_all()
    Gtk.main()


if __name__ == "__main__":
    main()
