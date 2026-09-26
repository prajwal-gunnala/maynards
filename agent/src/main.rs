//! `mesh`: the laptop side of MeshAI.
//!
//!   mesh join '<invite json from the Host QR>'   lend this laptop's memory to the Host phone
//!   mesh ask  "question"  [--host IP]           ask the model running on the Host
//!   mesh review FILE...   [--host IP]           review files
//!   mesh tests FILE [--out PATH] [--host IP]    write unit tests for a file
//!   mesh diff             [--host IP]           review staged git changes (or unstaged if none)
//!   mesh hook                                   run `mesh diff` before every git commit (advice only)
//!   mesh host [--port 8080]                     the laptop as the brain: QR pairing, models, split runs, chat
//!   mesh panel [--port 8080]                    web app: see the USB phones, run a model on each, chat with them
//!   mesh route --text IP:PORT --vision IP:PORT [--port 8080] [--lan]
//!                                               one address for several phones: photos go to the vision phone,
//!                                               text to the text phone; a chat page at http://localhost:8080/
//!
//! The Host phone runs the brain; this agent only follows it. Control messages are JSON lines
//! over TCP port 7070 (same as a Helper phone); layers travel over ggml RPC.

mod host;

use serde_json::{json, Value};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use std::{env, fs, thread};

pub const API_PORT: u16 = 8080;
const FILES_PORT: u16 = 8088;
const RPC_PORTS: [u16; 5] = [50052, 50062, 50070, 50080, 50100];

/// The one wording used to review a change, whether it is asked for from this terminal, from the
/// pre-commit hook, or by a phone over the link.
pub const REVIEW_PROMPT: &str =
    "Review this change. List real bugs first, then risky spots. Be brief; say 'looks fine' if it is.";

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    let host_flag = flag(&args, "--host");
    let result = match args.first().map(String::as_str) {
        Some("join") if args.len() >= 2 => join(&args[1]),
        Some("ask") if args.len() >= 2 => ask(&host(host_flag), &args[1]).map(|_| ()),
        Some("review") if args.len() >= 2 => files(&args[1..]).iter().try_for_each(|f| review(&host(host_flag.clone()), f)),
        Some("tests") if args.len() >= 2 => tests(&host(host_flag), &args[1], flag(&args, "--out")),
        Some("diff") => diff(&host(host_flag)),
        Some("hook") => hook(),
        Some("host") => host::host(flag(&args, "--port").and_then(|p| p.parse().ok()).unwrap_or(API_PORT)),
        Some("panel") => panel(flag(&args, "--port").and_then(|p| p.parse().ok()).unwrap_or(API_PORT)),
        Some("route") => route(flag(&args, "--text"), flag(&args, "--vision"),
            flag(&args, "--port").and_then(|p| p.parse().ok()).unwrap_or(API_PORT), args.iter().any(|a| a == "--lan")),
        _ => {
            eprintln!("usage:\n  mesh join '<invite json>'\n  mesh ask \"question\" [--host IP]\n  mesh review FILE... [--host IP]\n  mesh tests FILE [--out PATH] [--host IP]\n  mesh diff [--host IP]\n  mesh hook");
            std::process::exit(2);
        }
    };
    if let Err(e) = result {
        eprintln!("mesh: {e}");
        std::process::exit(1);
    }
}

type Res<T> = Result<T, String>;

fn flag(args: &[String], name: &str) -> Option<String> {
    args.iter().position(|a| a == name).and_then(|i| args.get(i + 1).cloned())
}

/// The Host's address: --host, else the one we last joined.
fn host(flag: Option<String>) -> String {
    flag.or_else(|| fs::read_to_string(config_dir().join("last-host")).ok().map(|s| s.trim().to_string()))
        .unwrap_or_else(|| "127.0.0.1".into())
}

fn config_dir() -> std::path::PathBuf {
    let d = std::path::PathBuf::from(env::var("HOME").unwrap_or_else(|_| ".".into())).join(".config/meshai");
    let _ = fs::create_dir_all(&d);
    d
}

// ---------------------------------------------------------------- join

fn join(invite_text: &str) -> Res<()> {
    let invite: Value = serde_json::from_str(invite_text).map_err(|e| format!("bad invite: {e}"))?;
    let mesh = invite["mesh"].as_str().unwrap_or("mesh").to_string();
    let port = invite["port"].as_u64().unwrap_or(7070) as u16;
    let hosts: Vec<String> = invite["hosts"].as_array().ok_or("invite has no hosts")?
        .iter().filter_map(|h| h.as_str().map(String::from)).collect();
    let secret_file = config_dir().join(format!("secret-{mesh}"));
    thread::spawn(serve_models);
    let mut backoff = 1;
    loop {
        match session(&hosts, port, &invite, &secret_file) {
            Ok(()) => eprintln!("link closed"),
            Err(e) if e.starts_with("refused") => return Err(e),
            Err(e) => eprintln!("{e}"),
        }
        thread::sleep(Duration::from_secs(backoff));
        backoff = (backoff * 2).min(15);
    }
}

fn session(hosts: &[String], port: u16, invite: &Value, secret_file: &std::path::Path) -> Res<()> {
    let sock = hosts.iter().find_map(|h| {
        let addr: SocketAddr = format!("{h}:{port}").parse().ok()?;
        TcpStream::connect_timeout(&addr, Duration::from_secs(4)).ok()
    }).ok_or("cannot reach the Host")?;
    sock.set_nodelay(true).ok();
    let host_ip = sock.peer_addr().map(|a| a.ip().to_string()).unwrap_or_default();
    let my_ip = sock.local_addr().map(|a| a.ip().to_string()).unwrap_or_default();
    let _ = fs::write(config_dir().join("last-host"), &host_ip);

    let out = Arc::new(Mutex::new(sock.try_clone().map_err(|e| e.to_string())?));
    let mut lines = BufReader::new(sock).lines();
    let secret = fs::read_to_string(secret_file).ok();
    send(&out, json!({"t": "hello", "id": device_id(), "token": invite["token"], "secret": secret, "specs": specs(),
        "models": shared_models(), "files_port": FILES_PORT}))?;

    let welcome: Value = next(&mut lines)?.ok_or("Host hung up")?;
    if welcome["t"] != "welcome" {
        return Err(format!("refused: {}", welcome["reason"].as_str().unwrap_or("?")));
    }
    let _ = fs::write(secret_file, welcome["secret"].as_str().unwrap_or(""));
    println!("joined {} ({host_ip}) as a helper", welcome["host"].as_str().unwrap_or("Host"));

    // report specs every 2 s
    let reporter = out.clone();
    thread::spawn(move || loop {
        thread::sleep(Duration::from_secs(2));
        if send(&reporter, json!({"t": "specs", "specs": specs()})).is_err() { break; }
    });

    let mut engine: Option<Child> = None;
    let result = loop {
        let m = match next(&mut lines) { Ok(Some(m)) => m, Ok(None) => break Ok(()), Err(e) => break Err(e) };
        match m["t"].as_str().unwrap_or("") {
            "ping" => { send(&out, json!({"t": "pong", "at": m["at"]}))?; }
            "run" => {
                stop(&mut engine);
                println!("holding layers {}", m["layers"].as_str().unwrap_or("?"));
                match start_helper(&my_ip) {
                    Ok((child, addr)) => {
                        engine = Some(child);
                        // behind NAT (an emulator Host) the Host must be told a different address
                        let addr = match env::var("MESH_ADVERTISE") {
                            Ok(ip) => format!("{ip}:{}", addr.rsplit(':').next().unwrap_or("50052")),
                            Err(_) => addr,
                        };
                        send(&out, json!({"t": "ready", "addr": addr}))?;
                    }
                    Err(e) => { send(&out, json!({"t": "failed", "reason": e}))?; }
                }
            }
            // the Host (a phone) asking this laptop what it has changed and not committed
            "diff" => {
                // 6 000 characters, because the phone's engine runs with a 4096 token context and the
                // answer needs room too. If we cut the patch, say so rather than review half of it silently.
                let (d, cut) = working_diff(6_000);
                println!("sent {} characters of diff to the Host{}", d.len(), if cut { " (truncated)" } else { "" });
                send(&out, json!({"t": "diff", "text": d, "cut": cut, "repo": repo_name(),
                                  "dir": env::current_dir().map(|p| p.display().to_string()).unwrap_or_default()}))?;
            }
            "stop" => { stop(&mut engine); println!("stopped"); }
            "bye" => break Err(format!("refused: {}", m["reason"].as_str().unwrap_or("?"))),
            _ => {}
        }
    };
    stop(&mut engine);
    result
}

fn next(lines: &mut std::io::Lines<BufReader<TcpStream>>) -> Res<Option<Value>> {
    match lines.next() {
        None => Ok(None),
        Some(Err(e)) => Err(e.to_string()),
        Some(Ok(l)) => serde_json::from_str(&l).map(Some).map_err(|e| e.to_string()),
    }
}

fn send(out: &Arc<Mutex<TcpStream>>, v: Value) -> Res<()> {
    let mut s = out.lock().unwrap();
    writeln!(s, "{v}").and_then(|_| s.flush()).map_err(|e| e.to_string())
}

/// Start ggml-rpc-server on the link address; try a few ports in case one is taken.
fn start_helper(bind: &str) -> Res<(Child, String)> {
    let bin = env::var("MESH_RPC_SERVER").unwrap_or_else(|_| "ggml-rpc-server".into());
    let threads = thread::available_parallelism().map(|n| n.get()).unwrap_or(4).saturating_sub(2).max(2);
    for port in RPC_PORTS {
        let mut cmd = Command::new(&bin);
        cmd.args(["-H", bind, "-p", &port.to_string(), "-t", &threads.to_string(), "-c"])
            .stdout(Stdio::null()).stderr(Stdio::null());
        // if this agent dies, the kernel stops the engine too, so no layers are left behind
        unsafe {
            use std::os::unix::process::CommandExt;
            cmd.pre_exec(|| { libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGTERM); Ok(()) });
        }
        let mut child = cmd
            .spawn().map_err(|e| format!("cannot start {bin}: {e} (set MESH_RPC_SERVER)"))?;
        let addr = format!("{bind}:{port}");
        for _ in 0..30 {
            let sa = match addr.parse::<std::net::SocketAddr>() {
                Ok(sa) => sa,
                // an empty or IPv6 bind address is not parseable here; try the next port rather
                // than panicking, which would kill the helper and make the host blame the phone
                Err(_) => break,
            };
            if TcpStream::connect_timeout(&sa, Duration::from_millis(300)).is_ok() {
                return Ok((child, addr));
            }
            if let Ok(Some(_)) = child.try_wait() { break; }
            thread::sleep(Duration::from_millis(200));
        }
        let _ = child.kill();
        let _ = child.wait();
    }
    Err("engine did not start".into())
}

fn stop(engine: &mut Option<Child>) {
    if let Some(mut c) = engine.take() {
        let _ = c.kill();
        let _ = c.wait();
    }
}

// ---------------------------------------------------------------- panel: drive the USB phones from a web page

const PANEL_PAGE: &str = include_str!("panel.html");
const PKG: &str = "ai.maynards.mesh";
const PHONE_MODELS: &str = "/sdcard/Android/data/ai.maynards.mesh/files/models";

type Ports = std::sync::Arc<Mutex<std::collections::BTreeMap<String, u16>>>;

fn adb(serial: &str, args: &[&str]) -> String {
    match Command::new("adb").arg("-s").arg(serial).args(args).output() {
        Ok(o) => {
            // a failed forward, an unauthorised device or a refused am start used to return ""
            // and be treated as success, so the phone silently did nothing
            if !o.status.success() {
                let err = String::from_utf8_lossy(&o.stderr).trim().to_string();
                eprintln!("adb {args:?} on {serial}: {}", if err.is_empty() { "failed".into() } else { err });
            }
            String::from_utf8_lossy(&o.stdout).replace('\r', "")
        }
        Err(e) => { eprintln!("adb not available: {e}"); String::new() }
    }
}

fn phones() -> Vec<String> {
    let out = Command::new("adb").arg("devices").output().map(|o| String::from_utf8_lossy(&o.stdout).to_string()).unwrap_or_default();
    out.lines().skip(1).filter_map(|l| {
        let mut it = l.split_whitespace();
        let (s, st) = (it.next()?, it.next()?);
        (st == "device" && !s.starts_with("emulator")).then(|| s.to_string())
    }).collect()
}

/// Each phone's API (its port 8080) is forwarded to a fixed laptop port, so it works without any network.
fn port_for(ports: &Ports, serial: &str) -> u16 {
    let mut p = ports.lock().unwrap();
    let next = 18081 + p.len() as u16;
    let port = *p.entry(serial.to_string()).or_insert(next);
    adb(serial, &["forward", &format!("tcp:{port}"), "tcp:8080"]);
    port
}

fn running_model(port: u16) -> Option<String> {
    let (code, body) = http_get(&format!("127.0.0.1:{port}"), "/v1/models").ok()?;
    if code != 200 { return None; }
    let v: Value = serde_json::from_str(&body).ok()?;
    v["data"][0]["id"].as_str().map(|m| m.rsplit('/').next().unwrap_or(m).to_string())
}

fn is_vision(model: &str) -> bool {
    let m = model.to_ascii_lowercase();
    m.contains("-vl") || m.contains("vision") || m.contains("llava")
}

fn device(ports: &Ports, serial: &str) -> Value {
    let info = adb(serial, &["shell", &format!(
        "getprop ro.product.marketname; getprop ro.product.model; getprop ro.soc.model; \
         grep -E 'MemTotal|MemAvailable' /proc/meminfo; dumpsys battery | grep -E 'level:|powered: true'; \
         ls -l {PHONE_MODELS} 2>/dev/null")]);
    let lines: Vec<&str> = info.lines().collect();
    let get = |i: usize| lines.get(i).map(|s| s.trim()).unwrap_or("");
    let kb = |key: &str| lines.iter().find(|l| l.starts_with(key))
        .and_then(|l| l.split_whitespace().nth(1)).and_then(|v| v.parse::<f64>().ok()).unwrap_or(0.0);
    let battery = lines.iter().find(|l| l.trim().starts_with("level:"))
        .and_then(|l| l.split(':').nth(1)).and_then(|v| v.trim().parse::<i64>().ok()).unwrap_or(-1);
    let models: Vec<Value> = lines.iter().filter(|l| l.ends_with(".gguf") && !l.contains("mmproj-"))
        .filter_map(|l| {
            let f: Vec<&str> = l.split_whitespace().collect();
            let size: f64 = f.get(4)?.parse().ok()?;
            Some(json!({"file": f.last()?, "gb": (size / 1e8).round() / 10.0}))
        }).collect();
    let port = port_for(ports, serial);
    let running = running_model(port);
    json!({
        "serial": serial,
        "name": if get(0).is_empty() { get(1) } else { get(0) },
        "chip": get(2),
        "memTotalGb": (kb("MemTotal:") / 1e5).round() / 10.0,
        "memFreeGb": (kb("MemAvailable:") / 1e5).round() / 10.0,
        "battery": battery,
        "charging": lines.iter().any(|l| l.contains("powered: true")),
        "models": models,
        "port": port,
        "running": running,
        "vision": running.as_deref().map(is_vision).unwrap_or(false),
    })
}

fn panel(port: u16) -> Res<()> {
    let l = std::net::TcpListener::bind(("127.0.0.1", port)).map_err(|e| format!("port {port}: {e}"))?;
    println!("MeshAI panel on http://localhost:{port}/");
    let ports: Ports = Default::default();
    for s in l.incoming().flatten() {
        let ports = ports.clone();
        thread::spawn(move || { if let Err(e) = panel_one(s, &ports) { eprintln!("panel: {e}"); } });
    }
    Ok(())
}

fn panel_one(mut s: TcpStream, ports: &Ports) -> std::io::Result<()> {
    let (first, headers, body) = read_request(&s)?;
    let path = first.split_whitespace().nth(1).unwrap_or("/").to_string();
    let json_reply = |s: &mut TcpStream, v: Value| reply(s, "application/json", v.to_string().as_bytes());
    let req: Value = serde_json::from_slice(&body).unwrap_or(Value::Null);
    match path.as_str() {
        "/" | "/index.html" => reply(&mut s, "text/html; charset=utf-8", PANEL_PAGE.as_bytes()),
        "/api/devices" => {
            let list: Vec<Value> = phones().iter().map(|p| device(ports, p)).collect();
            json_reply(&mut s, Value::Array(list))
        }
        "/api/run" => {
            let (serial, model) = (req["serial"].as_str().unwrap_or(""), req["model"].as_str().unwrap_or(""));
            port_for(ports, serial);
            adb(serial, &["shell", "am", "start", "-S", "-n", &format!("{PKG}/.MainActivity"), "--es", "role", "HOST", "--es", "run", model]);
            json_reply(&mut s, json!({"ok": true}))
        }
        "/api/stop" => {
            adb(req["serial"].as_str().unwrap_or(""), &["shell", "am", "force-stop", PKG]);
            json_reply(&mut s, json!({"ok": true}))
        }
        "/api/status" => {
            // the chat tab's device chips
            let list: Vec<Value> = phones().iter().filter_map(|p| {
                let port = port_for(ports, p);
                let m = running_model(port)?;
                Some(json!({"role": if is_vision(&m) { "vision" } else { "text" }, "name": p, "model": m, "ok": true}))
            }).collect();
            json_reply(&mut s, Value::Array(list))
        }
        _ if path.starts_with("/v1/") => route_to_phones(s, &first, &headers, &body, ports),
        _ => write!(s, "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"),
    }
}

/// Photos to a USB phone running a vision model, everything else to one running a text model.
fn route_to_phones(mut s: TcpStream, first: &str, headers: &[String], body: &[u8], ports: &Ports) -> std::io::Result<()> {
    let wants_vision = String::from_utf8_lossy(body).contains("\"image_url\"");
    let mut text = None;
    let mut vision = None;
    for p in phones() {
        let port = port_for(ports, &p);
        if let Some(m) = running_model(port) {
            let t = format!("127.0.0.1:{port}");
            if is_vision(&m) { vision.get_or_insert((t, p)); } else { text.get_or_insert((t, p)); }
        }
    }
    let pick = if wants_vision { vision.clone().map(|v| ("vision", v)) } else { None }
        .or_else(|| text.clone().map(|t| ("text", t)))
        .or_else(|| vision.clone().map(|v| ("vision", v)));
    match pick {
        Some((role, (target, serial))) => forward(s, first, headers, body, role, &target, &serial),
        None => {
            let msg = json!({"error": {"message": "no model is running: start one first"}}).to_string();
            write!(s, "HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{msg}", msg.len())
        }
    }
}

fn read_request(s: &TcpStream) -> std::io::Result<(String, Vec<String>, Vec<u8>)> {
    let mut r = BufReader::new(s.try_clone()?);
    let mut first = String::new();
    r.read_line(&mut first)?;
    let mut headers = Vec::new();
    let mut len = 0usize;
    loop {
        let mut h = String::new();
        if r.read_line(&mut h)? == 0 || h == "\r\n" { break; }
        let lower = h.to_ascii_lowercase();
        if let Some(v) = lower.strip_prefix("content-length:") { len = v.trim().parse().unwrap_or(0); }
        if !lower.starts_with("host:") && !lower.starts_with("connection:") { headers.push(h); }
    }
    let mut body = vec![0u8; len];
    r.read_exact(&mut body)?;
    Ok((first, headers, body))
}

fn reply(s: &mut TcpStream, ctype: &str, b: &[u8]) -> std::io::Result<()> {
    reply_status(s, 200, ctype, b)
}

fn reply_status(s: &mut TcpStream, code: u16, ctype: &str, b: &[u8]) -> std::io::Result<()> {
    let text = match code { 200 => "OK", 404 => "Not Found", 503 => "Service Unavailable", _ => "Error" };
    write!(s, "HTTP/1.1 {code} {text}\r\nContent-Type: {ctype}\r\nContent-Length: {}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n", b.len())?;
    s.write_all(b)
}

/// Passes one request to a phone and streams the answer back, tagged with which phone answered.
fn forward(mut s: TcpStream, first: &str, headers: &[String], body: &[u8], role: &str, target: &str, who: &str) -> std::io::Result<()> {
    let mut up = TcpStream::connect(target)?;
    write!(up, "{first}Host: {target}\r\nConnection: close\r\n")?;
    for h in headers { up.write_all(h.as_bytes())?; }
    up.write_all(b"\r\n")?;
    up.write_all(body)?;
    let mut ur = BufReader::new(up);
    let mut status = String::new();
    ur.read_line(&mut status)?;
    s.write_all(status.as_bytes())?;
    write!(s, "X-Mesh-Route: {role}:{who}\r\nAccess-Control-Expose-Headers: X-Mesh-Route\r\n")?;
    std::io::copy(&mut ur, &mut s)?;
    Ok(())
}

// ---------------------------------------------------------------- routing between phones

const CHAT_PAGE: &str = include_str!("chat.html");

/// One front door for several single-model phones. Requests with an image go to the vision phone,
/// everything else to the text phone; the answer streams straight back. Adds `X-Mesh-Route: role:addr`.
fn route(text: Option<String>, vision: Option<String>, port: u16, lan: bool) -> Res<()> {
    if text.is_none() && vision.is_none() { return Err("give --text IP:PORT and/or --vision IP:PORT".into()); }
    let bind = if lan { "0.0.0.0" } else { "127.0.0.1" };
    let l = std::net::TcpListener::bind((bind, port)).map_err(|e| format!("port {port}: {e}"))?;
    println!("MeshAI router on http://{bind}:{port}/  text -> {}  vision -> {}",
        text.as_deref().unwrap_or("-"), vision.as_deref().unwrap_or("-"));
    let targets = std::sync::Arc::new((text, vision));
    for s in l.incoming().flatten() {
        let t = targets.clone();
        thread::spawn(move || { if let Err(e) = route_one(s, &t.0, &t.1) { eprintln!("route: {e}"); } });
    }
    Ok(())
}

fn route_one(mut s: TcpStream, text: &Option<String>, vision: &Option<String>) -> std::io::Result<()> {
    let mut r = BufReader::new(s.try_clone()?);
    let mut first = String::new();
    r.read_line(&mut first)?;
    let mut headers = Vec::new();
    let mut len = 0usize;
    loop {
        let mut h = String::new();
        if r.read_line(&mut h)? == 0 || h == "\r\n" { break; }
        let lower = h.to_ascii_lowercase();
        if let Some(v) = lower.strip_prefix("content-length:") { len = v.trim().parse().unwrap_or(0); }
        if !lower.starts_with("host:") && !lower.starts_with("connection:") { headers.push(h); }
    }
    let mut body = vec![0u8; len];
    r.read_exact(&mut body)?;
    let path = first.split_whitespace().nth(1).unwrap_or("/").to_string();
    let reply = |s: &mut TcpStream, ctype: &str, b: &[u8]| -> std::io::Result<()> {
        write!(s, "HTTP/1.1 200 OK\r\nContent-Type: {ctype}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", b.len())?;
        s.write_all(b)
    };
    if first.starts_with("GET") && (path == "/" || path == "/index.html") {
        return reply(&mut s, "text/html; charset=utf-8", CHAT_PAGE.as_bytes());
    }
    if path == "/api/status" {
        let probe = |role: &str, addr: &Option<String>| -> Option<Value> {
            let a = addr.as_ref()?;
            let ok = http_get(a, "/health").map(|(c, _)| c == 200).unwrap_or(false);
            let model = http_get(a, "/v1/models").ok().and_then(|(_, b)| serde_json::from_str::<Value>(&b).ok())
                .and_then(|v| v["data"][0]["id"].as_str().map(|m| m.rsplit('/').next().unwrap_or(m).to_string()));
            Some(json!({"role": role, "name": format!("{role} phone ({a})"), "ok": ok, "model": model}))
        };
        let v: Vec<Value> = [probe("text", text), probe("vision", vision)].into_iter().flatten().collect();
        return reply(&mut s, "application/json", Value::Array(v).to_string().as_bytes());
    }
    // photos go to the vision phone, everything else to the text phone (or whichever exists)
    let wants_vision = String::from_utf8_lossy(&body).contains("\"image_url\"");
    let (role, target) = match (wants_vision, text, vision) {
        (true, _, Some(v)) => ("vision", v),
        (_, Some(t), _) => ("text", t),
        (_, None, Some(v)) => ("vision", v),
        _ => unreachable!(),
    };
    let mut up = TcpStream::connect(target.as_str())?;
    write!(up, "{first}Host: {target}\r\nConnection: close\r\n")?;
    for h in &headers { up.write_all(h.as_bytes())?; }
    up.write_all(b"\r\n")?;
    up.write_all(&body)?;
    let mut ur = BufReader::new(up);
    let mut status = String::new();
    ur.read_line(&mut status)?;
    s.write_all(status.as_bytes())?;
    write!(s, "X-Mesh-Route: {role}:{target}\r\nAccess-Control-Expose-Headers: X-Mesh-Route\r\n")?;
    std::io::copy(&mut ur, &mut s)?;
    Ok(())
}

/// Minimal GET for status checks: (status code, body).
fn http_get(addr: &str, path: &str) -> std::io::Result<(u16, String)> {
    let sa: SocketAddr = addr.parse().map_err(|_| std::io::Error::other("bad address"))?;
    let mut s = TcpStream::connect_timeout(&sa, Duration::from_secs(2))?;
    s.set_read_timeout(Some(Duration::from_secs(3)))?;
    write!(s, "GET {path} HTTP/1.1\r\nHost: {addr}\r\nConnection: close\r\n\r\n")?;
    let mut buf = String::new();
    s.read_to_string(&mut buf)?;
    let code = buf.split_whitespace().nth(1).and_then(|c| c.parse().ok()).unwrap_or(0);
    let body = buf.split_once("\r\n\r\n").map(|x| x.1.to_string()).unwrap_or_default();
    Ok((code, body))
}

// ---------------------------------------------------------------- sharing models

/// The folder whose GGUF files this laptop offers to the Host (MESH_MODELS, default ~/models).
fn models_dir() -> std::path::PathBuf {
    env::var("MESH_MODELS").map(Into::into)
        .unwrap_or_else(|_| std::path::PathBuf::from(env::var("HOME").unwrap_or_default()).join("models"))
}

fn shared_models() -> Value {
    let mut v: Vec<Value> = fs::read_dir(models_dir()).into_iter().flatten().flatten()
        .filter_map(|e| {
            let name = e.file_name().to_string_lossy().to_string();
            let len = e.metadata().ok()?.len();
            name.ends_with(".gguf").then(|| json!({"file": name, "bytes": len}))
        })
        .collect();
    v.sort_by_key(|m| m["file"].as_str().unwrap_or("").to_string());
    Value::Array(v)
}

/// A tiny HTTP server so the Host phone can pull model files over the private link:
/// GET /models/<file.gguf>, with Range for resuming. Only .gguf names, no paths.
fn serve_models() {
    let Ok(l) = std::net::TcpListener::bind(("0.0.0.0", FILES_PORT)) else {
        eprintln!("cannot share models on port {FILES_PORT}");
        return;
    };
    for s in l.incoming().flatten() {
        thread::spawn(move || { let _ = serve_one(s); });
    }
}

fn serve_one(mut s: TcpStream) -> std::io::Result<()> {
    let mut r = BufReader::new(s.try_clone()?);
    let mut first = String::new();
    r.read_line(&mut first)?;
    let mut start: u64 = 0;
    loop {
        let mut h = String::new();
        if r.read_line(&mut h)? == 0 || h == "\r\n" { break; }
        if let Some(v) = h.to_ascii_lowercase().strip_prefix("range: bytes=") {
            start = v.trim().trim_end_matches('-').split('-').next().and_then(|n| n.parse().ok()).unwrap_or(0);
        }
    }
    let name = first.split_whitespace().nth(1).and_then(|p| p.strip_prefix("/models/")).unwrap_or("");
    let ok_name = name.ends_with(".gguf") && !name.contains('/') && !name.contains("..") && !name.contains('%');
    let path = models_dir().join(name);
    let Ok(mut f) = fs::File::open(&path).map_err(|_| ()).and_then(|f| if ok_name { Ok(f) } else { Err(()) }) else {
        return s.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    };
    let total = f.metadata()?.len();
    let start = start.min(total);
    use std::io::Seek;
    f.seek(std::io::SeekFrom::Start(start))?;
    let status = if start > 0 { "206 Partial Content" } else { "200 OK" };
    write!(s, "HTTP/1.1 {status}\r\nContent-Type: application/octet-stream\r\nContent-Length: {}\r\n\
               Content-Range: bytes {start}-{}/{total}\r\nConnection: close\r\n\r\n", total - start, total.saturating_sub(1))?;
    std::io::copy(&mut f, &mut s)?;
    Ok(())
}

// ---------------------------------------------------------------- specs

fn device_id() -> String {
    fs::read_to_string("/etc/machine-id").map(|s| format!("laptop-{}", &s.trim()[..12.min(s.trim().len())]))
        .unwrap_or_else(|_| "laptop".into())
}

fn specs() -> Value {
    let mem = fs::read_to_string("/proc/meminfo").unwrap_or_default();
    let kb = |key: &str| -> u64 {
        mem.lines().find(|l| l.starts_with(key))
            .and_then(|l| l.split_whitespace().nth(1)).and_then(|v| v.parse().ok()).unwrap_or(0)
    };
    let cpu = fs::read_to_string("/proc/cpuinfo").unwrap_or_default();
    let chip = cpu.lines().find(|l| l.starts_with("model name"))
        .and_then(|l| l.split(':').nth(1)).map(|s| s.trim().to_string()).unwrap_or_else(|| "CPU".into());
    let max_khz: f64 = fs::read_to_string("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")
        .ok().and_then(|s| s.trim().parse().ok()).unwrap_or(0.0);
    let battery: i64 = fs::read_to_string("/sys/class/power_supply/BAT0/capacity")
        .ok().and_then(|s| s.trim().parse().ok()).unwrap_or(100);
    let charging = ["AC", "AC0", "ADP1", "ACAD"].iter().any(|a| {
        fs::read_to_string(format!("/sys/class/power_supply/{a}/online")).map(|s| s.trim() == "1").unwrap_or(false)
    }) || battery == 100;
    let name = fs::read_to_string("/sys/class/dmi/id/product_name").map(|s| s.trim().to_string())
        .unwrap_or_else(|_| "Laptop".into());
    json!({
        "id": device_id(), "name": name, "kind": "laptop", "chip": chip,
        "cores": thread::available_parallelism().map(|n| n.get()).unwrap_or(1),
        "maxGhz": max_khz / 1e6,
        "totalBytes": kb("MemTotal:") * 1024, "freeBytes": kb("MemAvailable:") * 1024,
        "heat": -1.0, "battery": battery, "charging": charging,
    })
}

// ---------------------------------------------------------------- ask

/// The file arguments, without flags and their values.
fn files(args: &[String]) -> Vec<String> {
    let mut out = Vec::new();
    let mut skip = false;
    for a in args {
        if skip { skip = false; continue; }
        if a.starts_with("--") { skip = true; continue; }
        out.push(a.clone());
    }
    out
}

fn review(host: &str, file: &str) -> Res<()> {
    let code = fs::read_to_string(file).map_err(|e| format!("{file}: {e}"))?;
    eprintln!("== {file}");
    ask(host, &format!("Review this file ({file}). List real bugs first, then risky spots, briefly.\n\n```\n{code}\n```")).map(|_| ())
}

fn tests(host: &str, file: &str, out: Option<String>) -> Res<()> {
    let code = fs::read_to_string(file).map_err(|e| format!("{file}: {e}"))?;
    let framework = match file.rsplit('.').next().unwrap_or("") {
        "py" => "pytest", "rs" => "Rust #[test] functions", "js" | "ts" => "Jest", "kt" => "JUnit", "go" => "Go testing",
        _ => "the usual test framework for this language",
    };
    let text = ask(host, &format!(
        "Write unit tests for this file ({file}) using {framework}. Cover normal cases, edge cases and errors. \
         Reply with one code block only.\n\n```\n{code}\n```"))?;
    if let Some(path) = out {
        let body = text.split("```").nth(1).map(|b| b.split_once('\n').map(|x| x.1).unwrap_or(b)).unwrap_or(&text);
        fs::write(&path, body).map_err(|e| format!("{path}: {e}"))?;
        eprintln!("wrote {path}");
    }
    Ok(())
}

/// What this machine has changed and not committed: staged if there is anything staged, otherwise the
/// working tree. Empty means nothing to review.
///
/// `limit` is in characters and exists because the prompt has to fit the context the engine was started
/// with. At about four characters to the token, 6 000 characters is roughly 1 500 tokens, which leaves
/// room in a 4096 context for the review itself. The terminal asks for more because it is usually
/// talking to a host that has a bigger context to spend.
pub fn working_diff(limit: usize) -> (String, bool) {
    let git = |args: &[&str]| Command::new("git").args(args).output()
        .map(|o| String::from_utf8_lossy(&o.stdout).to_string()).unwrap_or_default();
    let mut d = git(&["diff", "--cached"]);
    if d.trim().is_empty() { d = git(&["diff"]); }
    let cut = d.chars().count() > limit;
    (d.chars().take(limit).collect(), cut)
}

/// The repository this agent is sitting in, by name, so the phone can say what it is reviewing.
pub fn repo_name() -> String {
    Command::new("git").args(["rev-parse", "--show-toplevel"]).output().ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .and_then(|p| p.rsplit('/').next().map(String::from))
        .unwrap_or_default()
}

fn diff(host: &str) -> Res<()> {
    let (d, cut) = working_diff(24_000);
    if d.trim().is_empty() { eprintln!("nothing to review"); return Ok(()); }
    if cut { eprintln!("note: the change is large, so only the first 24000 characters are reviewed"); }
    ask(host, &format!("{REVIEW_PROMPT}\n\n```diff\n{d}\n```")).map(|_| ())
}

/// Installs a pre-commit hook that prints a review of the staged change. It never blocks the commit.
fn hook() -> Res<()> {
    let dir = Command::new("git").args(["rev-parse", "--git-path", "hooks"]).output().map_err(|e| e.to_string())?;
    let dir = String::from_utf8_lossy(&dir.stdout).trim().to_string();
    if dir.is_empty() { return Err("not inside a git repository".into()); }
    let path = std::path::Path::new(&dir).join("pre-commit");
    if path.exists() { return Err(format!("{} already exists; add `mesh diff || true` to it yourself", path.display())); }
    let me = env::current_exe().map_err(|e| e.to_string())?;
    fs::write(&path, format!("#!/bin/sh\n# MeshAI: review the staged change on your own devices (advice only)\n\"{}\" diff || true\n", me.display()))
        .map_err(|e| e.to_string())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&path, fs::Permissions::from_mode(0o755)).map_err(|e| e.to_string())?;
    }
    println!("installed {}", path.display());
    Ok(())
}

/// Streams an answer from the Host's OpenAI-compatible API and prints it as it arrives.
fn ask(host: &str, question: &str) -> Res<String> {
    let body = json!({
        "messages": [{"role": "user", "content": question}],
        "stream": true, "max_tokens": 1024,
    }).to_string();
    let mut s = TcpStream::connect((host, API_PORT)).map_err(|e| format!("cannot reach {host}:{API_PORT}: {e}"))?;
    write!(s, "POST /v1/chat/completions HTTP/1.1\r\nHost: {host}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len())
        .map_err(|e| e.to_string())?;
    let mut r = BufReader::new(s);
    let mut status = String::new();
    r.read_line(&mut status).map_err(|e| e.to_string())?;
    if !status.contains(" 200 ") {
        let mut rest = String::new();
        let _ = r.read_to_string(&mut rest);
        return Err(format!("{}{}", status.trim(), rest.lines().last().map(|l| format!(": {l}")).unwrap_or_default()));
    }
    let mut stdout = std::io::stdout();
    let mut timings = Value::Null;
    let mut text = String::new();
    for line in r.lines() {
        let line = line.map_err(|e| e.to_string())?;
        let Some(data) = line.strip_prefix("data: ") else { continue };
        if data == "[DONE]" { break; }
        let Ok(v) = serde_json::from_str::<Value>(data) else { continue };
        if let Some(t) = v["choices"][0]["delta"]["content"].as_str() {
            text.push_str(t);
            print!("{t}");
            let _ = stdout.flush();
        }
        if !v["timings"].is_null() { timings = v["timings"].clone(); }
    }
    println!();
    if let Some(tps) = timings["predicted_per_second"].as_f64() {
        eprintln!("[{:.1} tok/s, {} tokens]", tps, timings["predicted_n"]);
    }
    Ok(text)
}
