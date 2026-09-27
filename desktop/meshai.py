#!/usr/bin/env python3
"""MeshAI for Linux: an agent workspace on top of the mesh.

The window is a thin shell. The UI is desktop/ui/index.html, served with the task runner by desktop/service.py on
127.0.0.1 (random port, a token only this window knows). The brain is still `mesh host` (agent/src/host.rs): this
window starts it if nothing answers and leaves it running when it closes. The page asks the window for the two
things a web page cannot do: a native folder picker, and the full mesh dashboard in its own window.
"""
import fcntl
import json
import os
import subprocess
import sys
import time
import traceback
import urllib.request

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
gi.require_version("WebKit2", "4.1")
from gi.repository import Gdk, Gtk, WebKit2  # noqa: E402

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import service  # noqa: E402

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HOST = os.environ.get("MESH_HOST_URL", "http://localhost:8080")
CTX_FOR_AIDER = 16384
LOG = os.path.expanduser("~/.cache/meshai/app.log")
BG = Gdk.RGBA(9 / 255, 9 / 255, 11 / 255, 1)


def log(msg):
    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    with open(LOG, "a") as f:
        f.write(time.strftime("%H:%M:%S ") + msg + "\n")


sys.excepthook = lambda k, v, tb: (log("".join(traceback.format_exception(k, v, tb))), sys.__excepthook__(k, v, tb))


def single_instance():
    """A second copy would start a second task runner on the same projects: refuse it."""
    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    lock = open(os.path.join(os.path.dirname(LOG), "app.lock"), "w")
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        return None
    return lock


def ensure_host():
    """Start `mesh host` only if none answers and none is running (one may still be starting): never two."""
    try:
        urllib.request.urlopen(HOST + "/api/state", timeout=3).read()
        return
    except Exception:
        pass
    if subprocess.run(["pgrep", "-x", "mesh"], capture_output=True).returncode == 0:
        return
    env = dict(os.environ, MESH_CTX=os.environ.get("MESH_CTX", str(CTX_FOR_AIDER)),
               MESH_MODELS=os.environ.get("MESH_MODELS", os.path.expanduser("~/meshai-models")))
    out = open("/tmp/mesh-host.log", "a")
    subprocess.Popen([os.path.join(REPO, "agent", "target", "release", "mesh"), "host", "--port", "8080"],
                     cwd=REPO, env=env, stdout=out, stderr=out, start_new_session=True)
    log("started mesh host")


class Window(Gtk.Window):
    def __init__(self, url):
        super().__init__(title="MeshAI")
        self.set_default_size(1480, 920)
        manager = WebKit2.UserContentManager()
        manager.register_script_message_handler("mesh")
        manager.connect("script-message-received::mesh", self.on_message)
        self.web = WebKit2.WebView.new_with_user_content_manager(manager)
        self.web.set_background_color(BG)
        self.web.load_uri(url)
        self.add(self.web)
        self.dash = None

    def on_message(self, _manager, result):
        try:
            msg = json.loads(result.get_js_value().to_string())
        except Exception:
            log(traceback.format_exc())
            return
        if msg.get("kind") == "pick":
            self.pick_folder()
        elif msg.get("kind") == "dashboard":
            self.open_dashboard()

    def pick_folder(self):
        dlg = Gtk.FileChooserDialog(title="Choose a project (a git repository)", transient_for=self,
                                    action=Gtk.FileChooserAction.SELECT_FOLDER)
        dlg.add_buttons("Cancel", Gtk.ResponseType.CANCEL, "Choose", Gtk.ResponseType.OK)
        dlg.set_current_folder(os.path.expanduser("~"))
        if dlg.run() == Gtk.ResponseType.OK and dlg.get_filename():
            self.web.evaluate_javascript(f"window.onPicked({json.dumps(dlg.get_filename())})", -1, None, None, None, None, None)
        dlg.destroy()

    def open_dashboard(self):
        """The full mesh dashboard (the host's own page) in its own window."""
        if self.dash:
            self.dash.present()
            return
        self.dash = Gtk.Window(title="MeshAI · Mesh")
        self.dash.set_default_size(1280, 860)
        view = WebKit2.WebView()
        view.set_background_color(BG)
        view.load_uri(HOST + "/")
        self.dash.add(view)
        self.dash.connect("destroy", lambda *_: setattr(self, "dash", None))
        self.dash.show_all()


def main():
    lock = single_instance()
    if lock is None:
        print("MeshAI is already open.")
        return
    ensure_host()
    svc = service.Service()
    port = service.serve(svc)
    log(f"app start: service on 127.0.0.1:{port}")
    Gtk.Settings.get_default().set_property("gtk-application-prefer-dark-theme", True)
    win = Window(f"http://127.0.0.1:{port}/#k={svc.token}")
    win.connect("destroy", Gtk.main_quit)
    win.show_all()
    Gtk.main()
    del lock


if __name__ == "__main__":
    main()
