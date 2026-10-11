#!/usr/bin/env python3
"""Show which cable each phone's mesh traffic really uses, once a second.

Usage: scripts/link-watch.py SERIAL [SERIAL ...]

For each phone (over adb):
  - bytes/s on each network interface (wlan0 = Wi-Fi, rndis0/ncm0/usb0 = USB tethering, tun0 = VPN), from /proc/net/dev
  - every open mesh TCP connection (control 7070, model RPC 5005x-5010x, API 8080) and the interface its
    local address belongs to, from /proc/net/tcp and /proc/net/tcp6

One TCP connection is bound to one local address, so it travels over exactly one interface; Wi-Fi and USB
connections can exist side by side, but a single connection never splits between them.
"""
import subprocess
import sys
import time

PORTS = {7070: "control", 8080: "API"} | {p: "RPC" for p in (50052, 50062, 50070, 50080, 50100)}
IFACES = ("wlan0", "rndis0", "ncm0", "usb0", "tun0")
NAMES = {"wlan0": "Wi-Fi", "rndis0": "USB", "ncm0": "USB", "usb0": "USB", "tun0": "VPN"}


def sh(serial: str, cmd: str) -> str:
    return subprocess.run(["adb", "-s", serial, "shell", cmd], capture_output=True, text=True, timeout=5).stdout


def counters(serial: str) -> dict[str, tuple[int, int]]:
    out = {}
    for line in sh(serial, "cat /proc/net/dev").splitlines():
        if ":" not in line:
            continue
        name, data = line.split(":", 1)
        name = name.strip()
        if name in IFACES:
            f = data.split()
            out[name] = (int(f[0]), int(f[8]))
    return out


def addresses(serial: str) -> dict[str, str]:
    """local IPv4 -> interface name"""
    out = {}
    for line in sh(serial, "ip -4 -o addr").splitlines():
        f = line.split()
        if len(f) > 3:
            out[f[3].split("/")[0]] = f[1]
    return out


def hexip(h: str) -> str:
    return ".".join(str(int(h[i:i + 2], 16)) for i in (6, 4, 2, 0))


def v4(h: str) -> str | None:
    """IPv4 from a /proc/net/tcp or tcp6 address; tcp6 holds IPv4 sockets as ::ffff:a.b.c.d."""
    if len(h) == 8:
        return hexip(h)
    if len(h) == 32 and h[:24] == "0000000000000000FFFF0000":
        return hexip(h[24:])
    return None


def connections(serial: str, addrs: dict[str, str]) -> list[str]:
    rows = []
    lines = sh(serial, "cat /proc/net/tcp /proc/net/tcp6").splitlines()
    for line in lines:
        f = line.split()
        if len(f) < 4 or f[3] != "01" or ":" not in f[1]:      # 01 = ESTABLISHED
            continue
        lip, lport = f[1].split(":")
        rip, rport = f[2].split(":")
        lport, rport = int(lport, 16), int(rport, 16)
        kind = PORTS.get(lport) or PORTS.get(rport)
        if not kind:
            continue
        local, remote = v4(lip), v4(rip)
        if local is None or remote is None or local.startswith("127."):
            continue
        iface = addrs.get(local, "?")
        rows.append(f"{kind:<7} {local}:{lport} ↔ {remote}:{rport}  via {NAMES.get(iface, iface)} ({iface})")
    return sorted(set(rows))


def main(serials: list[str]) -> None:
    last = {s: counters(s) for s in serials}
    t_last = time.time()
    while True:
        time.sleep(1)
        now = time.time()
        dt = now - t_last
        t_last = now
        lines = [time.strftime("%H:%M:%S")]
        for s in serials:
            cur = counters(s)
            rates = []
            for name in IFACES:
                if name in cur and name in last[s]:
                    rx = (cur[name][0] - last[s][name][0]) / dt / 1024
                    tx = (cur[name][1] - last[s][name][1]) / dt / 1024
                    rates.append(f"{NAMES[name]} {name} ↓{rx:7.1f} ↑{tx:7.1f} KB/s")
            last[s] = cur
            lines.append(f"  {s}")
            lines += [f"    {r}" for r in rates] or ["    (no network interface up)"]
            conns = connections(s, addresses(s))
            lines += [f"    {c}" for c in conns] or ["    no mesh connections"]
        print("\n".join(lines), flush=True)


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
