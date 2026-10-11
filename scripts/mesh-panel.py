#!/usr/bin/env python3
"""Laptop panel for a phone-hosted mesh: what each phone's app reports, live, in a browser.

Usage: scripts/mesh-panel.py SERIAL [SERIAL ...] [--port 8770]   then open http://localhost:8770

Every 2 s, over adb, for each phone: the app's own files/status.json (role, run, split, Helpers, Ned Capsules,
last answers, Spider Sense; no prompt or answer text), memory, battery and temperature, and bytes per second
on Wi-Fi / USB / VPN. Read-only: the panel never sends anything to the phones.
"""
import argparse
import json
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATUS = "/sdcard/Android/data/ai.maynards.mesh/files/status.json"
IFACES = {"wlan0": "Wi-Fi", "rndis0": "USB", "ncm0": "USB", "usb0": "USB", "tun0": "VPN"}
state = {"phones": {}, "at": 0}


def sh(serial, cmd):
    try:
        return subprocess.run(["adb", "-s", serial, "shell", cmd], capture_output=True, text=True, timeout=6).stdout
    except Exception:
        return ""


def counters(text):
    out = {}
    for line in text.splitlines():
        if ":" in line:
            name, data = line.split(":", 1)
            name = name.strip()
            if name in IFACES:
                f = data.split()
                out[name] = (int(f[0]), int(f[8]))
    return out


def poll(serials):
    last = {}
    while True:
        t = time.time()
        for s in serials:
            raw = sh(s, f"cat {STATUS}; echo '@@@'; cat /proc/net/dev; echo '@@@'; "
                        "grep -E 'MemTotal|MemAvailable' /proc/meminfo; dumpsys battery | grep -E ' level:| temperature:'")
            parts = raw.split("@@@")
            if len(parts) < 3:
                state["phones"][s] = {"serial": s, "offline": True}
                continue
            try:
                status = json.loads(parts[0].strip() or "{}")
            except json.JSONDecodeError:
                status = {}
            c = counters(parts[1])
            rates = {}
            if s in last:
                dt = t - last[s][0]
                for k, (rx, tx) in c.items():
                    if k in last[s][1]:
                        prx, ptx = last[s][1][k]
                        rates[IFACES[k] + " " + k] = [round((rx - prx) / dt / 1024, 1), round((tx - ptx) / dt / 1024, 1)]
            last[s] = (t, c)
            vit = {}
            for line in parts[2].splitlines():
                bits = line.replace(":", " ").split()
                if bits[:1] == ["MemTotal"]: vit["ram_gb"] = round(int(bits[1]) / 1e6, 1)
                if bits[:1] == ["MemAvailable"]: vit["free_gb"] = round(int(bits[1]) / 1e6, 1)
                if bits[:1] == ["level"]: vit["battery"] = int(bits[1])
                if bits[:1] == ["temperature"]: vit["temp_c"] = int(bits[1]) / 10
            age = (time.time() * 1000 - status.get("at", 0)) / 1000 if status.get("at") else None
            state["phones"][s] = {"serial": s, "status": status, "rates": rates, "vitals": vit,
                                  "status_age_s": round(age, 1) if age is not None else None}
        state["at"] = time.time()
        time.sleep(max(0.0, 2 - (time.time() - t)))


PAGE = r"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Mesh Panel</title><style>
:root{--bg:#f6f6f3;--card:#fff;--ink:#1b1b1f;--muted:#6b6b73;--line:#e3e3de;--ok:#15803d;--warn:#b45309;--bad:#b91c1c;--helper:#7c3aed}
@media (prefers-color-scheme:dark){:root{--bg:#121214;--card:#1c1c20;--ink:#ececf0;--muted:#9a9aa3;--line:#2c2c32}}
body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.45 system-ui,sans-serif}
main{max-width:1100px;margin:0 auto;padding:16px}
h1{font-size:20px;margin:4px 0 14px} .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:12px}
.card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:12px}
.lab{font:600 11px/1 ui-monospace,monospace;letter-spacing:.08em;color:var(--muted);margin-bottom:8px;text-transform:uppercase}
.mono{font:12px/1.5 ui-monospace,monospace} .muted{color:var(--muted)} .ok{color:var(--ok)} .warn{color:var(--warn)} .bad{color:var(--bad)}
.bar{display:flex;height:22px;border-radius:6px;overflow:hidden;margin:6px 0}
.bar div{display:flex;align-items:center;justify-content:center;color:#fff;font:600 11px ui-monospace,monospace}
table{width:100%;border-collapse:collapse} td,th{text-align:left;padding:3px 6px;border-bottom:1px solid var(--line);font:12px ui-monospace,monospace}
.dot{display:inline-block;width:8px;height:8px;border-radius:4px;margin-right:6px}
</style></head><body><main><h1>Mesh Panel <span class="muted mono" id="t"></span></h1>
<div class="grid" id="phones"></div><div class="grid" style="margin-top:12px">
<div class="card"><div class="lab">Split</div><div id="split" class="mono muted">no run</div></div>
<div class="card"><div class="lab">Ned Capsules</div><div id="caps" class="mono muted">none</div></div></div>
<div class="grid" style="margin-top:12px"><div class="card"><div class="lab">Last answers</div><div id="ans" class="mono muted">none yet</div></div>
<div class="card"><div class="lab">Spider Sense</div><div id="sense" class="mono muted">nothing yet</div></div></div>
<p class="muted mono">Read-only. Everything here is what the phones' apps report, plus adb vitals and per-cable traffic.</p></main>
<script>
const esc=s=>String(s??'').replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
const f=(x,d=1)=>x==null||isNaN(x)?'–':Number(x).toFixed(d);
async function tick(){let d;try{d=await (await fetch('/api')).json()}catch(e){return}
 document.getElementById('t').textContent=new Date(d.at*1000).toLocaleTimeString();
 const ps=Object.values(d.phones);let host=null;
 document.getElementById('phones').innerHTML=ps.map(p=>{const s=p.status||{},v=p.vitals||{};if(s.role==='host')host=s;
  const role=s.role||'?';const col=role==='host'?'var(--ok)':role==='helper'?'var(--helper)':'var(--muted)';
  const rates=Object.entries(p.rates||{}).map(([k,[rx,tx]])=>`<div>${esc(k)} ↓${f(rx)} ↑${f(tx)} KB/s</div>`).join('');
  const work=s.rpc_age_ms>=0&&s.rpc_age_ms<2500?'<span class="ok">computing</span>':'';
  const line=role==='host'?`${esc(s.status)} · ${esc(s.model||'no model')} · ${s.threads||'–'} threads${s.threads_from_profile?' (profile)':''}`
   :role==='helper'?`${esc(s.link)} · ${esc(s.transport)} → ${esc(s.host)} · layers ${esc(s.layers||'–')} · ${esc(s.engine)} ${work}`:'not started';
  return `<div class="card"><div class="lab"><span class="dot" style="background:${col}"></span>${esc(role)} · ${esc(s.phone||p.serial)}</div>
   <div class="mono">${line}</div><div class="mono muted">RAM ${f(v.free_gb)} / ${f(v.ram_gb)} GB free · battery ${v.battery??'–'}% · ${f(v.temp_c)} °C
   ${p.status_age_s!=null&&p.status_age_s>6?` · <span class="warn">status ${f(p.status_age_s,0)} s old</span>`:''}${p.offline?' · <span class="bad">offline</span>':''}</div>
   <div class="mono" style="margin-top:6px">${rates}</div></div>`}).join('');
 if(!host)return;
 const sp=host.split||[];const tot=sp.reduce((a,x)=>a+(x.to-x.from+1),0)||1;
 document.getElementById('split').innerHTML=sp.length?`<div class="bar">${sp.map((x,i)=>`<div style="flex:${x.to-x.from+1};background:${i?'var(--helper)':'var(--ok)'}">${x.to-x.from+1}</div>`).join('')}</div>`+
  sp.map(x=>`<div>${esc(x.name)} · layers ${x.from}–${x.to} · ${f(x.gb,2)} GB</div>`).join('')+
  (host.peers||[]).map(p=>`<div class="muted">${esc(p.name)} · ${esc(p.link)} ${esc(p.addr)} · ${f(p.rtt_ms)} ms · RPC #${p.rpc_events}</div>`).join(''):'no run';
 document.getElementById('caps').innerHTML=(host.capsules||[]).map(c=>`<div>${esc(c.name)}</div><div class="muted">proposed ${esc(c.proposed)}</div><div class="${c.applied.startsWith('layers')?'ok':'warn'}">applied ${esc(c.applied)}</div>`).join('')||'none';
 const a=host.answers||[];document.getElementById('ans').innerHTML=a.length?'<table><tr><th>time</th><th>task</th><th>tok/s</th><th>read tok/s</th><th>first</th><th>out</th></tr>'+
  a.slice().reverse().map(r=>`<tr><td>${new Date(r.at).toLocaleTimeString()}</td><td>${esc(r.task)}</td><td>${f(r.tps)}</td><td>${f(r.prompt_tps)}</td><td>${f(r.first_ms/1000)} s</td><td>${r.tokens}</td></tr>`).join('')+'</table>':'none yet';
 const kc={LINK:'var(--ok)',MODEL:'var(--warn)',WORK:'var(--helper)',ASK:'var(--ink)',FAIL:'var(--bad)'};
 document.getElementById('sense').innerHTML=(host.sense||[]).slice().reverse().map(e=>`<div><span class="dot" style="background:${kc[e.kind]}"></span>${new Date(e.at).toLocaleTimeString()} · ${esc(e.who)} · ${esc(e.text)}${e.kind==='WORK'&&e.count>1?' ×'+e.count:''}</div>`).join('')||'nothing yet';}
tick();setInterval(tick,2000);
</script></body></html>"""


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        body, kind = (json.dumps(state).encode(), "application/json") if self.path.startswith("/api") else (PAGE.encode(), "text/html; charset=utf-8")
        self.send_response(200)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("serials", nargs="+")
    ap.add_argument("--port", type=int, default=8770)
    a = ap.parse_args()
    threading.Thread(target=poll, args=(a.serials,), daemon=True).start()
    print(f"Mesh panel on http://localhost:{a.port}/ for {', '.join(a.serials)}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", a.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
