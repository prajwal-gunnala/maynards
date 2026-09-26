//! `mesh`: the laptop side of MeshAI.
//!
//!   mesh join '<invite json from the Host QR>'   lend this laptop's memory to the Host phone
//!   mesh ask  "question"  [--host IP]           ask the model running on the Host
//!   mesh review FILE      [--host IP]           ask the model to review a file
//!
//! The Host phone runs the brain; this agent only follows it. Control messages are JSON lines
//! over TCP port 7070 (same as a Helper phone); layers travel over ggml RPC.

use serde_json::{json, Value};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use std::{env, fs, thread};

const API_PORT: u16 = 8080;
const RPC_PORTS: [u16; 5] = [50052, 50062, 50070, 50080, 50100];

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    let host_flag = flag(&args, "--host");
    let result = match args.first().map(String::as_str) {
        Some("join") if args.len() >= 2 => join(&args[1]),
        Some("ask") if args.len() >= 2 => ask(&host(host_flag), &args[1]),
        Some("review") if args.len() >= 2 => review(&host(host_flag), &args[1]),
        _ => {
            eprintln!("usage:\n  mesh join '<invite json>'\n  mesh ask \"question\" [--host IP]\n  mesh review FILE [--host IP]");
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
    send(&out, json!({"t": "hello", "id": device_id(), "token": invite["token"], "secret": secret, "specs": specs()}))?;

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
                    Ok((child, addr)) => { engine = Some(child); send(&out, json!({"t": "ready", "addr": addr}))?; }
                    Err(e) => { send(&out, json!({"t": "failed", "reason": e}))?; }
                }
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
        let mut child = Command::new(&bin)
            .args(["-H", bind, "-p", &port.to_string(), "-t", &threads.to_string(), "-c"])
            .stdout(Stdio::null()).stderr(Stdio::null())
            .spawn().map_err(|e| format!("cannot start {bin}: {e} (set MESH_RPC_SERVER)"))?;
        let addr = format!("{bind}:{port}");
        for _ in 0..30 {
            if TcpStream::connect_timeout(&addr.parse().unwrap(), Duration::from_millis(300)).is_ok() {
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

fn review(host: &str, file: &str) -> Res<()> {
    let code = fs::read_to_string(file).map_err(|e| format!("{file}: {e}"))?;
    ask(host, &format!("Review this file ({file}). List real bugs first, then risky spots, briefly.\n\n```\n{code}\n```"))
}

/// Streams an answer from the Host's OpenAI-compatible API and prints it as it arrives.
fn ask(host: &str, question: &str) -> Res<()> {
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
    for line in r.lines() {
        let line = line.map_err(|e| e.to_string())?;
        let Some(data) = line.strip_prefix("data: ") else { continue };
        if data == "[DONE]" { break; }
        let Ok(v) = serde_json::from_str::<Value>(data) else { continue };
        if let Some(t) = v["choices"][0]["delta"]["content"].as_str() {
            print!("{t}");
            let _ = stdout.flush();
        }
        if !v["timings"].is_null() { timings = v["timings"].clone(); }
    }
    println!();
    if let Some(tps) = timings["predicted_per_second"].as_f64() {
        eprintln!("[{:.1} tok/s, {} tokens]", tps, timings["predicted_n"]);
    }
    Ok(())
}
