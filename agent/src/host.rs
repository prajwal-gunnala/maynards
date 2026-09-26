//! `mesh host`: the laptop as the brain. Phones join by scanning a QR (MeshAI → Helper → Scan to join), the laptop
//! reads its model files, works out what fits (Doable / Tight / Not possible, and who holds which layers), then runs
//! llama-server here with the phones' layers over ggml RPC. A web page at http://localhost:8080 drives it all.
//!
//! Control link: one JSON object per line over TCP 7070, the same protocol the phone app's Host speaks.

use serde_json::{json, Value};
use std::collections::{BTreeMap, HashMap, VecDeque};
use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use std::thread;

const CONTROL_PORT: u16 = 7070;
const ENGINE_PORT: u16 = 8090;
const HOST_PAGE: &str = include_str!("host.html");
const GB: f64 = 1e9;

// ---------------------------------------------------------------- state

struct Peer {
    name: String,
    addr: String,
    specs: Value,
    rtts: VecDeque<f64>,
    wire: Arc<Mutex<TcpStream>>,
}

#[derive(Clone, Default)]
struct Run {
    status: String, // idle | starting | loading | ready | failed
    step: String,
    model: String,
    plan: Value,
    started: Option<Instant>,
}

pub struct Hub {
    mesh: String,
    token: Mutex<String>,
    secrets: Mutex<HashMap<String, String>>,
    peers: Mutex<BTreeMap<String, Peer>>,
    replies: Mutex<HashMap<String, Value>>,
    activity: Mutex<VecDeque<(String, String)>>, // (time, line)
    run: Mutex<Run>,
    engine: Mutex<Option<Child>>,
    usb_ports: crate::Ports,
    models: Mutex<HashMap<String, (u64, Model)>>,
    run_gen: Mutex<u64>,
}

fn config() -> std::path::PathBuf {
    let d = std::path::PathBuf::from(std::env::var("HOME").unwrap_or_default()).join(".config/meshai");
    let _ = fs::create_dir_all(&d);
    d
}

fn now_ms() -> u64 { SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0) }

fn random_hex(n: usize) -> String {
    let mut b = vec![0u8; n];
    if let Ok(mut f) = fs::File::open("/dev/urandom") { let _ = f.read_exact(&mut b); }
    b.iter().map(|x| format!("{x:02x}")).collect()
}

impl Hub {
    fn say(&self, line: String) {
        let t = chrono_like();
        eprintln!("{t} {line}");
        let mut a = self.activity.lock().unwrap();
        a.push_back((t, line));
        while a.len() > 30 { a.pop_front(); }
    }

    fn save_secrets(&self) {
        let s = self.secrets.lock().unwrap();
        let _ = fs::write(config().join("host-secrets.json"), serde_json::to_string(&*s).unwrap_or_default());
    }

    fn invite(&self) -> Value {
        json!({"mesh": self.mesh, "hosts": laptop_ips().iter().map(|(ip, _)| ip).collect::<Vec<_>>(),
               "port": CONTROL_PORT, "token": *self.token.lock().unwrap()})
    }
}

fn chrono_like() -> String {
    let s = (now_ms() / 1000 + 5 * 3600 + 1800) % 86400; // IST, good enough for a log line
    format!("{:02}:{:02}:{:02}", s / 3600, (s / 60) % 60, s % 60)
}

/// The laptop's IPv4 addresses, private links first: USB tethering, then Wi-Fi, then the rest.
fn laptop_ips() -> Vec<(String, String)> {
    let out = Command::new("ip").args(["-4", "-o", "addr", "show"]).output()
        .map(|o| String::from_utf8_lossy(&o.stdout).to_string()).unwrap_or_default();
    let mut v: Vec<(String, String)> = out.lines().filter_map(|l| {
        let f: Vec<&str> = l.split_whitespace().collect();
        let (iface, ip) = (f.get(1)?.to_string(), f.get(3)?.split('/').next()?.to_string());
        let skip = iface == "lo" || iface.starts_with("docker") || iface.starts_with("br-") || iface.starts_with("veth")
            || iface.starts_with("tailscale") || iface.starts_with("virbr");
        (!skip).then_some((ip, iface))
    }).collect();
    let rank = |i: &str| if i.starts_with("enx") || i.starts_with("usb") || i.starts_with("rndis") { 0 }
        else if i.starts_with("wl") { 1 } else { 2 };
    v.sort_by_key(|(_, i)| rank(i));
    v
}

fn link_kind(iface_ip: &str) -> &'static str {
    laptop_ips().iter().find(|(ip, _)| ip == iface_ip).map(|(_, i)| {
        if i.starts_with("enx") || i.starts_with("usb") || i.starts_with("rndis") { "USB cable" } else if i.starts_with("wl") { "Wi-Fi" } else { "network" }
    }).unwrap_or("network")
}

// ---------------------------------------------------------------- the laptop's own specs

fn laptop_specs() -> Value {
    let mut v = crate::specs();
    v["name"] = json!(format!("{} (this laptop)", v["name"].as_str().unwrap_or("Laptop")));
    v
}

fn usable(specs: &Value) -> f64 {
    let total = specs["totalBytes"].as_f64().unwrap_or(0.0);
    let free = specs["freeBytes"].as_f64().unwrap_or(0.0);
    let reserve = (total * 0.15).clamp(0.8 * GB, 2.5 * GB);
    (free - reserve).max(0.0)
}

// ---------------------------------------------------------------- model files (GGUF header only)

#[derive(Clone)]
struct Model {
    file: String,
    name: String,
    bytes: u64,
    layers: Vec<u64>,
    other: u64,
    kv_per_token: u64,
}

fn read_gguf(path: &std::path::Path) -> Option<Model> {
    let f = fs::File::open(path).ok()?;
    let bytes = f.metadata().ok()?.len();
    let mut r = BufReader::with_capacity(1 << 16, f);
    let mut pos: u64 = 0;
    macro_rules! rd { ($n:expr) => {{ let mut b = [0u8; $n]; r.read_exact(&mut b).ok()?; pos += $n; b }}; }
    let u32_ = |b: [u8; 4]| u32::from_le_bytes(b) as u64;
    if &rd!(4) != b"GGUF" { return None; }
    let _version = u32_(rd!(4));
    let n_tensors = u64::from_le_bytes(rd!(8));
    let n_kv = u64::from_le_bytes(rd!(8));
    let mut meta: HashMap<String, Value> = HashMap::new();

    fn read_str(r: &mut BufReader<fs::File>, pos: &mut u64) -> Option<String> {
        let mut l = [0u8; 8]; r.read_exact(&mut l).ok()?; *pos += 8;
        let n = u64::from_le_bytes(l) as usize;
        let mut b = vec![0u8; n]; r.read_exact(&mut b).ok()?; *pos += n as u64;
        Some(String::from_utf8_lossy(&b).to_string())
    }
    fn skip(r: &mut BufReader<fs::File>, pos: &mut u64, n: u64) -> Option<()> {
        std::io::copy(&mut r.by_ref().take(n), &mut std::io::sink()).ok()?; *pos += n; Some(())
    }
    fn value(r: &mut BufReader<fs::File>, pos: &mut u64, t: u32) -> Option<Value> {
        let mut b8 = [0u8; 8]; let mut b4 = [0u8; 4];
        Some(match t {
            0 | 1 | 7 => { skip(r, pos, 1)?; Value::Null }
            2 | 3 => { skip(r, pos, 2)?; Value::Null }
            4 => { r.read_exact(&mut b4).ok()?; *pos += 4; json!(u32::from_le_bytes(b4)) }
            5 => { r.read_exact(&mut b4).ok()?; *pos += 4; json!(i32::from_le_bytes(b4)) }
            6 => { skip(r, pos, 4)?; Value::Null }
            8 => json!(read_str(r, pos)?),
            9 => {
                r.read_exact(&mut b4).ok()?; *pos += 4; let et = u32::from_le_bytes(b4);
                r.read_exact(&mut b8).ok()?; *pos += 8; let n = u64::from_le_bytes(b8);
                let w = match et { 0 | 1 | 7 => 1, 2 | 3 => 2, 4 | 5 | 6 => 4, 10 | 11 | 12 => 8, _ => 0 };
                if w > 0 && et != 4 && et != 5 { skip(r, pos, n * w)?; Value::Null }
                else if w > 0 {   // small integer arrays (per-layer head counts): keep the largest
                    let mut best = 0i64;
                    for _ in 0..n { r.read_exact(&mut b4).ok()?; *pos += 4; best = best.max(i32::from_le_bytes(b4) as i64); }
                    json!(best)
                } else { for _ in 0..n { value(r, pos, et)?; } Value::Null }
            }
            10 | 11 => { r.read_exact(&mut b8).ok()?; *pos += 8; json!(u64::from_le_bytes(b8)) }
            12 => { skip(r, pos, 8)?; Value::Null }
            _ => return None,
        })
    }
    for _ in 0..n_kv {
        let key = read_str(&mut r, &mut pos)?;
        let t = u32_(rd!(4)) as u32;
        let v = value(&mut r, &mut pos, t)?;
        meta.insert(key, v);
    }
    let mut names = Vec::new();
    let mut offsets = Vec::new();
    for _ in 0..n_tensors {
        names.push(read_str(&mut r, &mut pos)?);
        let dims = u32_(rd!(4));
        for _ in 0..dims { rd!(8); }
        rd!(4);
        offsets.push(u64::from_le_bytes(rd!(8)));
    }
    let align = meta.get("general.alignment").and_then(|v| v.as_u64()).unwrap_or(32);
    let data = pos.div_ceil(align) * align;
    let mut order: Vec<usize> = (0..offsets.len()).collect();
    order.sort_by_key(|&i| offsets[i]);
    let mut sizes = vec![0u64; names.len()];
    for (k, &i) in order.iter().enumerate() {
        let end = if k + 1 < order.len() { offsets[order[k + 1]] } else { bytes.saturating_sub(data) };
        sizes[i] = end.saturating_sub(offsets[i]);
    }
    let arch = meta.get("general.architecture").and_then(|v| v.as_str()).unwrap_or("").to_string();
    let num = |k: &str| meta.get(&format!("{arch}.{k}")).and_then(|v| v.as_u64());
    let n_layer = num("block_count")? as usize;
    let mut layers = vec![0u64; n_layer];
    let mut other = 0;
    for (i, n) in names.iter().enumerate() {
        match n.strip_prefix("blk.").and_then(|x| x.split('.').next()).and_then(|x| x.parse::<usize>().ok()) {
            Some(b) if b < n_layer => layers[b] += sizes[i],
            _ => other += sizes[i],
        }
    }
    let heads = num("attention.head_count").unwrap_or(1).max(1);
    let kv_heads = num("attention.head_count_kv").unwrap_or(heads);
    let k = num("attention.key_length").unwrap_or(num("embedding_length").unwrap_or(0) / heads);
    let v = num("attention.value_length").unwrap_or(k);
    let file = path.file_name()?.to_string_lossy().to_string();
    Some(Model {
        name: meta.get("general.name").and_then(|v| v.as_str()).map(String::from).unwrap_or(file.trim_end_matches(".gguf").into()),
        file, bytes, layers, other, kv_per_token: n_layer as u64 * kv_heads * (k + v) * 2,
    })
}

fn scan_models(hub: &Hub) -> Vec<Model> {
    let dir = crate::models_dir();
    let mut out = Vec::new();
    for e in fs::read_dir(&dir).into_iter().flatten().flatten() {
        let name = e.file_name().to_string_lossy().to_string();
        let low = name.to_ascii_lowercase();
        if !name.ends_with(".gguf") || ["mmproj", "tts", "asr", "whisper", "diffusion", "embed"].iter().any(|x| low.contains(x)) { continue; }
        let len = e.metadata().map(|m| m.len()).unwrap_or(0);
        let cached = hub.models.lock().unwrap().get(&name).filter(|(l, _)| *l == len).map(|(_, m)| m.clone());
        let m = cached.or_else(|| read_gguf(&e.path()).inspect(|m| { hub.models.lock().unwrap().insert(name.clone(), (len, m.clone())); }));
        if let Some(m) = m { out.push(m); }
    }
    out.sort_by_key(|m| m.bytes);
    out
}

// ---------------------------------------------------------------- the planner (same rules as the phone app)

struct Dev { id: String, name: String, usable: f64, rtt: f64, heat: f64, battery: i64, charging: bool, host: bool }

const HOST_RESERVE: f64 = 0.30 * GB;
const HELPER_RESERVE: f64 = 0.15 * GB;

fn plan(m: &Model, devs: &[Dev], ctx: u64) -> Value {
    let kv = (m.kv_per_token * ctx * 17 / 32) as f64; // 8-bit KV cache
    let kv_layer = kv / m.layers.len().max(1) as f64;
    let need = m.bytes as f64 + kv + HOST_RESERVE;
    let host = &devs[0];
    let biggest = m.layers.iter().copied().max().unwrap_or(0) as f64 + kv_layer;
    let mut skipped = serde_json::Map::new();
    let mut helpers: Vec<&Dev> = devs[1..].iter().filter(|d| {
        let why = if d.rtt > 60.0 { Some(format!("link too slow ({:.0} ms)", d.rtt)) }
            else if d.heat >= 0.95 { Some("too hot".into()) }
            else if !d.charging && d.battery >= 0 && d.battery < 20 { Some(format!("battery {}%", d.battery)) }
            else if d.usable - HELPER_RESERVE < biggest { Some("too little memory".into()) }
            else { None };
        if let Some(w) = &why { skipped.insert(d.name.clone(), json!(w)); }
        why.is_none()
    }).collect();
    helpers.sort_by(|a, b| b.usable.partial_cmp(&a.usable).unwrap());
    let verdict = |have: f64, need: f64| if have - need >= need * 0.15 { "doable" } else { "tight" };
    let slice = |d: &Dev, from: usize, to: usize, bytes: f64| json!({"id": d.id, "name": d.name, "from": from, "to": to, "gb": bytes / GB, "host": d.host});
    if host.usable >= need {
        return json!({"verdict": verdict(host.usable, need), "reason": "Runs on this laptop alone", "need_gb": need / GB,
            "slices": [slice(host, 0, m.layers.len(), m.bytes as f64)], "skipped": skipped});
    }
    // split: this laptop keeps embeddings, output head and the first layers; helpers continue in order
    let mut slices = Vec::new();
    let mut next = 0usize;
    let mut capacity = 0.0;
    for d in std::iter::once(host).chain(helpers.iter().copied()) {
        if next >= m.layers.len() { break; }
        let fixed = if d.host { m.other as f64 + HOST_RESERVE } else { HELPER_RESERVE };
        let cap = d.usable - fixed;
        let (from, mut used) = (next, 0.0);
        while next < m.layers.len() && used + m.layers[next] as f64 + kv_layer <= cap { used += m.layers[next] as f64 + kv_layer; next += 1; }
        if next > from || d.host {
            slices.push(slice(d, from, next, used + if d.host { m.other as f64 } else { 0.0 }));
            capacity += cap.max(0.0) + fixed;
        }
    }
    if next < m.layers.len() {
        let missing: f64 = (next..m.layers.len()).map(|i| m.layers[i] as f64 + kv_layer).sum();
        return json!({"verdict": "not_possible", "reason": format!("Short by {:.1} GB", missing / GB), "need_gb": need / GB, "slices": [], "skipped": skipped});
    }
    let n = slices.len();
    json!({"verdict": verdict(capacity, need + HELPER_RESERVE * (n as f64 - 1.0)), "reason": format!("Needs {n} devices"),
           "need_gb": need / GB, "slices": slices, "skipped": skipped})
}

fn devices(hub: &Hub) -> Vec<Dev> {
    let me = laptop_specs();
    // test switch: pretend the laptop has at most this much room, to force a split
    let cap = std::env::var("MESH_HOST_CAP_GB").ok().and_then(|v| v.parse::<f64>().ok()).map(|g| g * GB).unwrap_or(f64::MAX);
    let mut v = vec![Dev { id: "laptop".into(), name: me["name"].as_str().unwrap_or("This laptop").into(), usable: usable(&me).min(cap),
        rtt: 0.0, heat: 0.0, battery: me["battery"].as_i64().unwrap_or(100), charging: true, host: true }];
    for (id, p) in hub.peers.lock().unwrap().iter() {
        let mut r: Vec<f64> = p.rtts.iter().copied().collect();
        r.sort_by(|a, b| a.partial_cmp(b).unwrap());
        v.push(Dev { id: id.clone(), name: p.name.clone(), usable: usable(&p.specs), rtt: r.get(r.len() / 2).copied().unwrap_or(0.0),
            heat: p.specs["heat"].as_f64().unwrap_or(0.0), battery: p.specs["battery"].as_i64().unwrap_or(100),
            charging: p.specs["charging"].as_bool().unwrap_or(true), host: false });
    }
    v
}

// ---------------------------------------------------------------- control link: phones join here

fn serve_control(hub: Arc<Hub>) {
    let l = match TcpListener::bind(("0.0.0.0", CONTROL_PORT)) { Ok(l) => l, Err(e) => { hub.say(format!("cannot listen on {CONTROL_PORT}: {e}")); return; } };
    for s in l.incoming().flatten() {
        let hub = hub.clone();
        thread::spawn(move || { let _ = serve_phone(s, hub); });
    }
}

fn send(w: &Arc<Mutex<TcpStream>>, v: Value) -> std::io::Result<()> {
    let mut s = w.lock().unwrap();
    writeln!(s, "{v}")?;
    s.flush()
}

fn serve_phone(sock: TcpStream, hub: Arc<Hub>) -> std::io::Result<()> {
    sock.set_nodelay(true)?;
    sock.set_read_timeout(Some(Duration::from_secs(5)))?;
    let peer_ip = sock.peer_addr()?.ip().to_string();
    let my_ip = sock.local_addr()?.ip().to_string();
    let wire = Arc::new(Mutex::new(sock.try_clone()?));
    let mut lines = BufReader::new(sock.try_clone()?).lines();
    let hello: Value = match lines.next() { Some(Ok(l)) => serde_json::from_str(&l).unwrap_or(Value::Null), _ => return Ok(()) };
    if hello["t"] != "hello" { return Ok(()); }
    let id = hello["id"].as_str().unwrap_or("").to_string();
    // two identical phones would look the same: add the end of the device id ("Vivo I2501 ·a3f9")
    let tag: String = id.chars().rev().take(4).collect::<Vec<_>>().into_iter().rev().collect();
    let name = format!("{} ·{tag}", hello["specs"]["name"].as_str().unwrap_or("phone"));
    let known = hub.secrets.lock().unwrap().get(&id).cloned();
    let secret = if known.is_some() && hello["secret"].as_str() == known.as_deref() {
        known.unwrap()
    } else if hello["token"].as_str() == Some(hub.token.lock().unwrap().as_str()) {
        *hub.token.lock().unwrap() = random_hex(16);        // one-time: the next phone needs a fresh QR
        let s = random_hex(32);
        hub.secrets.lock().unwrap().insert(id.clone(), s.clone());
        hub.save_secrets();
        s
    } else {
        hub.say(format!("refused {name} ({peer_ip}): not paired, scan the current QR"));
        let _ = send(&wire, json!({"t": "bye", "reason": "not paired: scan the QR on the laptop again"}));
        return Ok(());
    };
    send(&wire, json!({"t": "welcome", "secret": secret, "host": "laptop", "mesh": hub.mesh}))?;
    hub.say(format!("✓ {name} joined over {} ({peer_ip})", link_kind(&my_ip)));
    hub.peers.lock().unwrap().insert(id.clone(), Peer { name: name.clone(), addr: peer_ip.clone(), specs: hello["specs"].clone(), rtts: VecDeque::new(), wire: wire.clone() });
    sock.set_read_timeout(Some(Duration::from_secs(60)))?;

    let alive = Arc::new(Mutex::new(true));
    { let (w, a) = (wire.clone(), alive.clone());
      thread::spawn(move || while *a.lock().unwrap() { if send(&w, json!({"t": "ping", "at": now_ms()})).is_err() { break; } thread::sleep(Duration::from_secs(2)); }); }

    for line in lines {
        let Ok(line) = line else { break };
        let Ok(m) = serde_json::from_str::<Value>(&line) else { continue };
        match m["t"].as_str().unwrap_or("") {
            "specs" => if let Some(p) = hub.peers.lock().unwrap().get_mut(&id) { p.specs = m["specs"].clone(); },
            "pong" => if let Some(p) = hub.peers.lock().unwrap().get_mut(&id) {
                p.rtts.push_back(now_ms().saturating_sub(m["at"].as_u64().unwrap_or(0)) as f64);
                while p.rtts.len() > 10 { p.rtts.pop_front(); }
            },
            "ready" | "failed" => { hub.replies.lock().unwrap().insert(id.clone(), m); }
            _ => {}
        }
    }
    *alive.lock().unwrap() = false;
    hub.peers.lock().unwrap().remove(&id);
    hub.replies.lock().unwrap().insert(id.clone(), json!({"t": "gone"}));
    // the model keeps running while the phone reconnects; if its layers are really gone the engine fails and the
    // watchdog below reports it
    let in_run = hub.run.lock().unwrap().plan["slices"].as_array().map(|s| s.iter().any(|x| x["id"] == id.as_str())).unwrap_or(false);
    hub.say(format!("✗ {name} control link lost{}", if in_run { " (model keeps running; it will reconnect)" } else { "" }));
    Ok(())
}

// ---------------------------------------------------------------- running a model

fn set_run(hub: &Hub, status: &str, step: &str) {
    let mut r = hub.run.lock().unwrap();
    r.status = status.into();
    r.step = step.into();
}

fn stop(hub: &Hub, why: &str) {
    *hub.run_gen.lock().unwrap() += 1;
    if let Some(mut c) = hub.engine.lock().unwrap().take() { let _ = c.kill(); let _ = c.wait(); }
    for p in hub.peers.lock().unwrap().values() { let _ = send(&p.wire, json!({"t": "stop"})); }
    let failed = why.starts_with("stopped:") || why.starts_with("failed");
    set_run(hub, if failed { "failed" } else { "idle" }, why);
    if !why.is_empty() { hub.say(why.to_string()); }
}

fn start(hub: Arc<Hub>, file: String) {
    stop(&hub, "");
    let gen = *hub.run_gen.lock().unwrap();
    let Some(m) = scan_models(&hub).into_iter().find(|m| m.file == file) else { set_run(&hub, "failed", "model not found"); return; };
    let p = plan(&m, &devices(&hub), 4096);
    if p["verdict"] == "not_possible" { set_run(&hub, "failed", p["reason"].as_str().unwrap_or("does not fit")); return; }
    { let mut r = hub.run.lock().unwrap();
      *r = Run { status: "starting".into(), step: "Planning".into(), model: m.name.clone(), plan: p.clone(), started: Some(Instant::now()) }; }
    hub.say(format!("run {}: {}", m.name, p["reason"].as_str().unwrap_or("")));
    thread::spawn(move || {
        let cancelled = || *hub.run_gen.lock().unwrap() != gen;
        let slices = p["slices"].as_array().cloned().unwrap_or_default();
        let helpers: Vec<Value> = slices.iter().filter(|s| !s["host"].as_bool().unwrap_or(false)).cloned().collect();
        let mut addrs = Vec::new();
        for s in &helpers {
            let (id, name) = (s["id"].as_str().unwrap_or(""), s["name"].as_str().unwrap_or(""));
            set_run(&hub, "starting", &format!("Starting {name}"));
            hub.replies.lock().unwrap().remove(id);
            let wire = hub.peers.lock().unwrap().get(id).map(|p| p.wire.clone());
            let Some(w) = wire else { stop(&hub, &format!("failed: {name} is not connected")); return; };
            let _ = send(&w, json!({"t": "run", "layers": format!("{}-{}", s["from"], s["to"].as_u64().unwrap_or(1) - 1), "model": m.name}));
            let t0 = Instant::now();
            let reply = loop {
                if cancelled() { return; }
                if let Some(r) = hub.replies.lock().unwrap().remove(id) { break Some(r); }
                if t0.elapsed() > Duration::from_secs(45) { break None; }
                thread::sleep(Duration::from_millis(200));
            };
            match reply {
                Some(r) if r["t"] == "ready" => {
                    let a = r["addr"].as_str().unwrap_or("").to_string();
                    hub.say(format!("✓ {name} ready at {a}, layers {}-{}", s["from"], s["to"].as_u64().unwrap_or(1) - 1));
                    addrs.push(a);
                }
                other => { stop(&hub, &format!("failed: {name} did not start ({})", other.map(|o| o["reason"].as_str().unwrap_or("no answer").to_string()).unwrap_or("no answer".into()))); return; }
            }
        }
        for (a, s) in addrs.iter().zip(&helpers) {
            let ok = (0..10).any(|_| { let ok = a.parse::<SocketAddr>().ok().and_then(|sa| TcpStream::connect_timeout(&sa, Duration::from_secs(1)).ok()).is_some(); if !ok { thread::sleep(Duration::from_millis(500)); } ok });
            if !ok { stop(&hub, &format!("failed: cannot reach {} at {a}", s["name"].as_str().unwrap_or(""))); return; }
        }
        // engine arguments: same rules as the phone app's EngineArgs
        let bin = std::env::var("MESH_LLAMA_BIN").unwrap_or_else(|_| "/mnt/storage/meshai/build/v3/host/bin".into());
        let threads = thread::available_parallelism().map(|n| n.get()).unwrap_or(4).saturating_sub(2).max(2);
        let mut args: Vec<String> = vec!["-m".into(), crate::models_dir().join(&m.file).to_string_lossy().into(), "-c".into(), "4096".into(),
            "-t".into(), threads.to_string(), "--host".into(), "127.0.0.1".into(), "--port".into(), ENGINE_PORT.to_string(),
            "--jinja".into(), "--fit".into(), "off".into(), "--reasoning".into(), "off".into(), "-np".into(), "1".into(),
            "-ctk".into(), "q8_0".into(), "-ctv".into(), "q8_0".into(), "-fa".into(), "on".into(),
            // read the file once, front to back: with mmap, sending layers to phones reads it in scattered pieces
            "--load-mode".into(), "none".into()];
        if helpers.is_empty() {
            args.extend(["-ngl".into(), "0".into()]);
        } else {
            let counts: Vec<u64> = helpers.iter().map(|s| s["to"].as_u64().unwrap_or(0) - s["from"].as_u64().unwrap_or(0)).collect();
            let off: u64 = counts.iter().sum();
            args.extend(["--rpc".into(), addrs.join(","), "-ngl".into(), (off + 1).to_string(),
                "--override-tensor".into(), "^(output|output_norm|token_embd)\\.(weight|bias)$=CPU".into()]);
            if counts.len() > 1 {
                let t = (off + 1) as f64;
                let parts: Vec<String> = counts.iter().enumerate().map(|(i, c)| format!("{:.6}", (*c + if i == counts.len() - 1 { 1 } else { 0 }) as f64 / t)).collect();
                args.extend(["--tensor-split".into(), parts.join(",")]);
            }
        }
        let log = fs::File::create("/tmp/mesh-host-engine.log").ok();
        let child = Command::new(format!("{bin}/llama-server")).args(&args)
            .stdout(log.as_ref().and_then(|f| f.try_clone().ok()).map(Stdio::from).unwrap_or(Stdio::null()))
            .stderr(log.map(Stdio::from).unwrap_or(Stdio::null())).spawn();
        match child { Ok(c) => *hub.engine.lock().unwrap() = Some(c), Err(e) => { stop(&hub, &format!("failed: engine: {e}")); return; } }
        set_run(&hub, "loading", if helpers.is_empty() { "Loading the model" } else { "Sending layers to the phones" });
        loop {
            if cancelled() { return; }
            if let Ok((200, _)) = crate::http_get(&format!("127.0.0.1:{ENGINE_PORT}"), "/health") { break; }
            let exited = hub.engine.lock().unwrap().as_mut().map(|c| c.try_wait().ok().flatten().is_some()).unwrap_or(true);
            if exited { stop(&hub, "failed: the engine stopped (log: /tmp/mesh-host-engine.log)"); return; }
            thread::sleep(Duration::from_secs(1));
        }
        let secs = hub.run.lock().unwrap().started.map(|t| t.elapsed().as_secs()).unwrap_or(0);
        set_run(&hub, "ready", &format!("Ready in {secs} s"));
        hub.say(format!("✓ {} ready in {secs} s", m.name));
        // watchdog: report if the engine itself stops (e.g. a phone's layers disappeared)
        loop {
            thread::sleep(Duration::from_secs(2));
            if cancelled() { return; }
            let exited = hub.engine.lock().unwrap().as_mut().map(|c| c.try_wait().ok().flatten().is_some()).unwrap_or(true);
            if exited { stop(&hub, "failed: the engine stopped (log: /tmp/mesh-host-engine.log)"); return; }
        }
    });
}

// ---------------------------------------------------------------- web page and API

pub fn host(port: u16) -> Result<(), String> {
    let secrets: HashMap<String, String> = fs::read_to_string(config().join("host-secrets.json")).ok()
        .and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
    let mesh = fs::read_to_string(config().join("mesh-id")).unwrap_or_else(|_| { let m = random_hex(4); let _ = fs::write(config().join("mesh-id"), &m); m });
    let hub = Arc::new(Hub {
        mesh: mesh.trim().into(), token: Mutex::new(random_hex(16)), secrets: Mutex::new(secrets), peers: Default::default(),
        replies: Default::default(), activity: Default::default(), run: Mutex::new(Run { status: "idle".into(), ..Default::default() }),
        engine: Default::default(), usb_ports: Default::default(), models: Default::default(), run_gen: Default::default(),
    });
    { let h = hub.clone(); thread::spawn(move || serve_control(h)); }
    thread::spawn(crate::serve_models);   // phones can also pull model files from here
    let l = TcpListener::bind(("127.0.0.1", port)).map_err(|e| format!("port {port}: {e}"))?;
    hub.say(format!("MeshAI host on http://localhost:{port}/  (phones join on port {CONTROL_PORT})"));
    for s in l.incoming().flatten() {
        let hub = hub.clone();
        thread::spawn(move || { let _ = http(s, hub); });
    }
    Ok(())
}

fn state(hub: &Hub) -> Value {
    let me = laptop_specs();
    let peers: Vec<Value> = hub.peers.lock().unwrap().iter().map(|(id, p)| {
        let mut r: Vec<f64> = p.rtts.iter().copied().collect();
        r.sort_by(|a, b| a.partial_cmp(b).unwrap());
        json!({"id": id, "name": p.name, "addr": p.addr, "specs": p.specs, "usable_gb": usable(&p.specs) / GB,
               "rtt_ms": r.get(r.len() / 2), "rtt_worst_ms": r.last()})
    }).collect();
    let devs = devices(hub);
    let models: Vec<Value> = scan_models(hub).iter().map(|m| {
        let p = plan(m, &devs, 4096);
        json!({"file": m.file, "name": m.name, "gb": m.bytes as f64 / GB, "layers": m.layers.len(), "plan": p})
    }).collect();
    let run = hub.run.lock().unwrap().clone();
    let invite = hub.invite();
    let qr = qrcode::QrCode::new(invite.to_string().as_bytes()).map(|c| c.render::<qrcode::render::svg::Color>()
        .min_dimensions(240, 240).quiet_zone(true).build()).unwrap_or_default();
    json!({
        "mesh": hub.mesh, "invite": invite, "qr": qr,
        "laptop": {"specs": me, "usable_gb": usable(&me) / GB},
        "peers": peers, "models": models,
        "pool_gb": devs.iter().map(|d| d.usable).sum::<f64>() / GB,
        "activity": hub.activity.lock().unwrap().iter().rev().map(|(t, l)| json!({"t": t, "line": l})).collect::<Vec<_>>(),
        "run": {"status": run.status, "step": run.step, "model": run.model, "plan": run.plan,
                "seconds": run.started.map(|t| t.elapsed().as_secs())},
    })
}

fn http(mut s: TcpStream, hub: Arc<Hub>) -> std::io::Result<()> {
    let (first, headers, body) = crate::read_request(&s)?;
    let path = first.split_whitespace().nth(1).unwrap_or("/").to_string();
    let req: Value = serde_json::from_slice(&body).unwrap_or(Value::Null);
    let ok = |s: &mut TcpStream, v: Value| crate::reply(s, "application/json", v.to_string().as_bytes());
    match path.as_str() {
        "/" | "/index.html" => crate::reply(&mut s, "text/html; charset=utf-8", HOST_PAGE.as_bytes()),
        "/api/state" => ok(&mut s, state(&hub)),
        "/api/run" => { start(hub.clone(), req["model"].as_str().unwrap_or("").into()); ok(&mut s, json!({"ok": true})) }
        "/api/stop" => { stop(&hub, "stopped"); ok(&mut s, json!({"ok": true})) }
        "/api/forget" => {
            let id = req["id"].as_str().unwrap_or("");
            hub.secrets.lock().unwrap().remove(id);
            hub.save_secrets();
            if let Some(p) = hub.peers.lock().unwrap().remove(id) { let _ = send(&p.wire, json!({"t": "bye", "reason": "forgotten"})); }
            ok(&mut s, json!({"ok": true}))
        }
        // one model per phone, over USB (the "Vision + 8B" option)
        "/api/usb/devices" => ok(&mut s, Value::Array(crate::phones().iter().map(|p| crate::device(&hub.usb_ports, p)).collect())),
        "/api/usb/run" => {
            let (serial, model) = (req["serial"].as_str().unwrap_or(""), req["model"].as_str().unwrap_or(""));
            crate::port_for(&hub.usb_ports, serial);
            crate::adb(serial, &["shell", "am", "start", "-S", "-n", &format!("{}/.MainActivity", crate::PKG), "--es", "role", "HOST", "--es", "run", model]);
            hub.say(format!("{serial}: starting {model} on the phone"));
            ok(&mut s, json!({"ok": true}))
        }
        "/api/usb/stop" => { crate::adb(req["serial"].as_str().unwrap_or(""), &["shell", "am", "force-stop", crate::PKG]); ok(&mut s, json!({"ok": true})) }
        "/api/newqr" => { *hub.token.lock().unwrap() = random_hex(16); ok(&mut s, json!({"ok": true})) }
        _ if path.starts_with("/v1/") => {
            let photo = String::from_utf8_lossy(&body).contains("\"image_url\"");
            let usb_vision = photo && crate::phones().iter().any(|p| crate::running_model(crate::port_for(&hub.usb_ports, p)).map(|m| crate::is_vision(&m)).unwrap_or(false));
            if hub.run.lock().unwrap().status == "ready" && !usb_vision {
                crate::forward(s, &first, &headers, &body, "mesh", &format!("127.0.0.1:{ENGINE_PORT}"), "laptop")
            } else {
                crate::route_to_phones(s, &first, &headers, &body, &hub.usb_ports)
            }
        }
        _ => write!(s, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"),
    }
}
