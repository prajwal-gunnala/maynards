#!/usr/bin/env python3
"""MeshAI for Linux: the laptop's window onto the mesh.

Left: the same dashboard the browser shows (agent/src/host.html, served by `mesh host`), drawn by WebKit, so the
app and the web page are one design and one set of fixes. Right: real terminals, with Aider working on a project
against the model the mesh runs, plus a shell and the logs. The brain is still `mesh host`; this window starts it
if nothing answers, and closing the window leaves the host and the phones running.
"""
import json
import os
import subprocess
import sys
import threading
import time
import traceback
import urllib.request

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
gi.require_version("Vte", "2.91")
gi.require_version("WebKit2", "4.1")
from gi.repository import Gdk, GLib, Gtk, Pango, Vte, WebKit2  # noqa: E402

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HOST = os.environ.get("MESH_HOST_URL", "http://localhost:8080")
AIDER = os.path.join(REPO, "desktop", "aider", "run-aider.sh")
DEMO = os.path.expanduser("~/meshai-aider-demo")
CTX_FOR_AIDER = 16384
DASH_MIN = 920
WORK_W = 600
LOG = os.path.expanduser("~/.cache/meshai/app.log")

# the dashboard's own palette (host.html :root), so the native half matches it
CSS = b"""
window, box, notebook, notebook header, notebook stack { background: #09090b; color: #ededed; }
headerbar { background: #111113; border-bottom: 1px solid #26262a; box-shadow: none; }
headerbar .title { color: #ededed; font-weight: 600; }
headerbar .subtitle { color: #8b8b93; font-family: monospace; }
.tools { background: #111113; border-bottom: 1px solid #26262a; padding: 8px 10px; }
.k { color: #63636b; font-family: monospace; font-size: 11px; }
.note { color: #8b8b93; font-family: monospace; font-size: 12px; }
.ok { color: #4ade80; } .warn { color: #fbbf24; } .bad { color: #f87171; }
button { background: #18181b; color: #ededed; border: 1px solid #26262a; border-radius: 7px; box-shadow: none; padding: 4px 12px; }
button:hover { background: #26262a; }
button.primary { background: #ededed; color: #09090b; border-color: #ededed; font-weight: 600; }
button.primary:hover { background: #ffffff; }
entry { background: #18181b; color: #ededed; border: 1px solid #26262a; border-radius: 7px; font-family: monospace; }
notebook header { border-bottom: 1px solid #26262a; }
notebook tab { padding: 6px 14px; color: #8b8b93; }
notebook tab:checked { color: #ededed; box-shadow: inset 0 -2px #4ade80; }
paned > separator { background: #26262a; min-width: 1px; }
"""


# ---------------------------------------------------------------- errors go to a file, so "it broke" has a cause

def log(msg):
    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    with open(LOG, "a") as f:
        f.write(time.strftime("%H:%M:%S ") + msg + "\n")


def excepthook(kind, value, tb):
    log("".join(traceback.format_exception(kind, value, tb)))
    sys.__excepthook__(kind, value, tb)


sys.excepthook = excepthook


# ---------------------------------------------------------------- host

def api(path, body=None, timeout=4):
    req = urllib.request.Request(HOST + path, data=None if body is None else json.dumps(body).encode(),
                                 method="GET" if body is None else "POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read() or b"{}")


def ensure_host():
    """Start `mesh host` only if none answers and none is running (one may be busy starting): never two."""
    try:
        api("/api/state")
        return "host running"
    except Exception:
        pass
    if subprocess.run(["pgrep", "-x", "mesh"], capture_output=True).returncode == 0:
        return "host starting"
    env = dict(os.environ, MESH_CTX=os.environ.get("MESH_CTX", str(CTX_FOR_AIDER)),
               MESH_MODELS=os.environ.get("MESH_MODELS", os.path.expanduser("~/meshai-models")))
    out = open("/tmp/mesh-host.log", "a")
    subprocess.Popen([os.path.join(REPO, "agent", "target", "release", "mesh"), "host", "--port", "8080"],
                     cwd=REPO, env=env, stdout=out, stderr=out, start_new_session=True)
    log("started mesh host")
    return "host started"


# ---------------------------------------------------------------- widgets

def styled(widget, *classes):
    for c in classes:
        widget.get_style_context().add_class(c)
    return widget


def set_tone(widget, tone):
    ctx = widget.get_style_context()
    for c in ("ok", "warn", "bad"):
        ctx.remove_class(c)
    if tone:
        ctx.add_class(tone)


def terminal():
    t = Vte.Terminal()
    t.set_font(Pango.FontDescription("Monospace 11"))
    t.set_scrollback_lines(10000)
    t.set_color_background(Gdk.RGBA(9 / 255, 9 / 255, 11 / 255, 1))
    t.set_color_foreground(Gdk.RGBA(237 / 255, 237 / 255, 237 / 255, 1))
    t.set_color_cursor(Gdk.RGBA(74 / 255, 222 / 255, 128 / 255, 1))
    return t


def spawn(term, argv, cwd):
    envv = [f"{k}={v}" for k, v in os.environ.items()]
    term.spawn_async(Vte.PtyFlags.DEFAULT, cwd, argv, envv, GLib.SpawnFlags.SEARCH_PATH,
                     None, None, -1, None, None, None)


def in_scroll(child):
    sw = Gtk.ScrolledWindow()
    sw.add(child)
    return sw


# ---------------------------------------------------------------- the window

class MeshWindow(Gtk.Window):
    def __init__(self, note):
        super().__init__(title="MeshAI")
        self.set_default_size(1600, 960)
        self.state = {}
        self.aider_running = False

        self.bar = Gtk.HeaderBar(title="MeshAI", subtitle=note)
        self.bar.set_show_close_button(True)
        self.pin_btn = Gtk.Button(label="Pin split")
        self.pin_btn.set_tooltip_text("Same layers on the same phones every run; starts when they have all joined")
        self.pin_btn.connect("clicked", self.on_pin)
        self.bar.pack_end(self.pin_btn)
        self.side_btn = Gtk.ToggleButton(label="Terminal", active=True)
        self.side_btn.connect("toggled", lambda b: self.work.set_visible(b.get_active()))
        self.bar.pack_end(self.side_btn)
        self.set_titlebar(self.bar)

        paned = Gtk.Paned(orientation=Gtk.Orientation.HORIZONTAL)
        dash = self.build_dashboard()
        dash.set_size_request(DASH_MIN, -1)   # the dashboard keeps its side menu from 900 px up (host.html @media)
        self.work = self.build_work()
        self.work.set_size_request(WORK_W, -1)
        paned.pack1(dash, True, False)        # extra width goes to the dashboard; the terminal keeps its size
        paned.pack2(self.work, False, False)
        self.add(paned)

        threading.Thread(target=self.poll, daemon=True).start()

    # ------------------------------------------------ left: the web dashboard itself
    def build_dashboard(self):
        self.web = WebKit2.WebView()
        self.web.set_background_color(Gdk.RGBA(9 / 255, 9 / 255, 11 / 255, 1))
        self.web.connect("load-failed", self.on_web_failed)
        self.web.load_uri(HOST + "/")
        return self.web

    def on_web_failed(self, _web, _event, uri, error):
        log(f"dashboard load failed: {uri}: {error.message}")
        self.web.load_html(
            "<body style='background:#09090b;color:#8b8b93;font:14px monospace;padding:40px'>"
            "Waiting for the host on " + HOST + " …</body>", None)
        GLib.timeout_add_seconds(3, lambda: (self.web.load_uri(HOST + "/"), False)[1])
        return True

    # ------------------------------------------------ right: Aider, shell, log
    def build_work(self):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        tools = styled(Gtk.Box(spacing=8), "tools")
        tools.pack_start(styled(Gtk.Label(label="PROJECT"), "k"), False, False, 0)
        self.folder = Gtk.FileChooserButton(title="Project folder", action=Gtk.FileChooserAction.SELECT_FOLDER)
        if os.path.isdir(DEMO):
            self.folder.set_filename(DEMO)
        tools.pack_start(self.folder, False, False, 0)
        tools.pack_start(styled(Gtk.Label(label="TESTS"), "k"), False, False, 0)
        self.test_cmd = Gtk.Entry(text="python3 -m pytest -q", width_chars=22)
        tools.pack_start(self.test_cmd, False, False, 0)
        start = styled(Gtk.Button(label="Start Aider"), "primary")
        start.connect("clicked", self.on_aider)
        tools.pack_start(start, False, False, 0)
        box.pack_start(tools, False, False, 0)
        self.note = styled(Gtk.Label(label="", xalign=0), "note")
        self.note.set_ellipsize(Pango.EllipsizeMode.END)
        self.note.set_margin_start(12)
        self.note.set_margin_top(6)
        self.note.set_margin_bottom(6)
        box.pack_start(self.note, False, False, 0)

        self.tabs = Gtk.Notebook()
        self.aider_term = terminal()
        self.aider_term.connect("child-exited", self.on_aider_exit)
        self.aider_term.feed(b"\r\n  Choose a project (a git repo) and press Start Aider.\r\n"
                             b"  Aider edits the code with the model the mesh runs, then runs the tests.\r\n")
        self.tabs.append_page(in_scroll(self.aider_term), Gtk.Label(label="Aider"))
        self.shell_term = terminal()
        spawn(self.shell_term, [os.environ.get("SHELL", "/bin/bash")], self.project())
        self.tabs.append_page(in_scroll(self.shell_term), Gtk.Label(label="Shell"))
        self.log_term = terminal()
        spawn(self.log_term, ["tail", "-n", "40", "-F", "/tmp/mesh-host.log", "/tmp/mesh-host-engine.log", LOG], REPO)
        self.tabs.append_page(in_scroll(self.log_term), Gtk.Label(label="Log"))
        box.pack_start(self.tabs, True, True, 0)
        return box

    def project(self):
        return self.folder.get_filename() or (DEMO if os.path.isdir(DEMO) else os.path.expanduser("~"))

    def say(self, text, tone=None):
        self.note.set_text(text)
        set_tone(self.note, tone)

    # ------------------------------------------------ actions
    def on_aider(self, *_):
        folder = self.project()
        if not os.path.isdir(os.path.join(folder, ".git")):
            return self.say("Not a git repo: Aider needs one", "bad")
        run = self.state.get("run") or {}
        if run.get("status") != "ready":
            return self.say("No model running yet: press Run on the left first", "warn")
        if (self.state.get("ctx") or 0) < CTX_FOR_AIDER:
            return self.say(f"Host context is {self.state.get('ctx')} tokens; Aider needs {CTX_FOR_AIDER}: "
                            f"restart the host with MESH_CTX={CTX_FOR_AIDER}", "warn")
        if self.aider_running:
            return self.say("Aider is already running", "warn")
        self.aider_term.reset(True, True)
        self.tabs.set_current_page(0)
        spawn(self.aider_term, [AIDER, self.test_cmd.get_text() or "pytest -q"], folder)
        self.aider_running = True
        self.say(f"Aider · {run.get('model', '')} · {folder}", "ok")
        self.aider_term.grab_focus()

    def on_aider_exit(self, *_):
        self.aider_running = False
        self.say("Aider stopped")

    def on_pin(self, *_):
        pin = self.state.get("pinned") or {}
        running = (self.state.get("run") or {}).get("status") == "ready"
        name = (self.state.get("run") or {}).get("model")
        file = next((m["file"] for m in self.state.get("models") or [] if m.get("name") == name), None)

        def go():
            try:
                if pin:
                    api("/api/unpin", {})
                elif running and file:
                    r = api("/api/pin", {"model": file})
                    if not r.get("ok"):
                        GLib.idle_add(self.say, f"Pin: {r.get('error')}", "bad")
                else:
                    GLib.idle_add(self.say, "Run a model first, then pin its split", "warn")
            except Exception as e:
                log(f"pin: {e}")
                GLib.idle_add(self.say, f"Pin failed: {e}", "bad")
        threading.Thread(target=go, daemon=True).start()

    # ------------------------------------------------ status in the title bar
    def poll(self):
        while True:
            try:
                s = api("/api/state")
                GLib.idle_add(self.render, s)
            except Exception as e:
                GLib.idle_add(self.bar.set_subtitle, f"host not answering ({e.__class__.__name__}); retrying")
            time.sleep(2)

    def render(self, s):
        try:
            self.state = s
            run = s.get("run") or {}
            tps = next((c.get("tps") for c in reversed(s.get("chats") or []) if c.get("tps")), None)
            n = len(s.get("peers") or []) + 1
            pin = s.get("pinned") or {}
            self.bar.set_subtitle(" · ".join(x for x in [
                run.get("model") or "no model", run.get("status") or "idle", f"{tps:.1f} tok/s" if tps else "",
                f"{n} device" + ("s" if n > 1 else ""), f"context {s.get('ctx')}" if s.get("ctx") else "",
                "📌 pinned" if pin else ""] if x))
            self.pin_btn.set_label("Unpin" if pin else "Pin split")
        except Exception:
            log(traceback.format_exc())
        return False


def main():
    style = Gtk.CssProvider()
    style.load_from_data(CSS)
    Gtk.StyleContext.add_provider_for_screen(Gdk.Screen.get_default(), style, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
    Gtk.Settings.get_default().set_property("gtk-application-prefer-dark-theme", True)
    log("app start")
    win = MeshWindow(ensure_host())
    win.connect("destroy", Gtk.main_quit)
    win.show_all()
    Gtk.main()


if __name__ == "__main__":
    main()
