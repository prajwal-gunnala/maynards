#!/usr/bin/env python3
"""Relay the phone Host/Helper TCP links over their separate USB-tether networks.

Usage: scripts/phone-usb-relay.py HOST_SERIAL HELPER_SERIAL [--join-helper]

The Host stays on the phone. The laptop forwards Helper control traffic to Host:7070
and Host RPC traffic back to Helper:50052 (plus the app's fallback RPC ports).
"""
import argparse
import asyncio
import json
import re
import shlex
import subprocess
import sys

RPC_PORTS = (50052, 50062, 50070, 50080, 50100)


def run(*args: str) -> str:
    return subprocess.run(args, check=True, text=True, capture_output=True).stdout.strip()


def phone_usb_ip(serial: str) -> str:
    output = run("adb", "-s", serial, "shell", "ip -4 -o addr show")
    for line in output.splitlines():
        fields = line.split()
        if len(fields) > 3 and re.match(r"^(rndis|usb|ncm)", fields[1]):
            return fields[3].split("/")[0]
    raise RuntimeError(f"{serial}: USB tethering is not up")


def laptop_usb_ip(phone_ip: str) -> str:
    output = run("ip", "-4", "route", "get", phone_ip)
    match = re.search(r"\bsrc (\S+)", output)
    if not match:
        raise RuntimeError(f"no laptop route to tethered phone {phone_ip}: {output}")
    return match.group(1)


async def pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while data := await reader.read(256 * 1024):
            writer.write(data)
            await writer.drain()
    except (ConnectionError, asyncio.CancelledError):
        pass
    finally:
        try:
            writer.write_eof()
        except (AttributeError, OSError, RuntimeError):
            pass


async def forward(client_reader: asyncio.StreamReader, client_writer: asyncio.StreamWriter,
                  target_ip: str, target_port: int) -> None:
    peer = client_writer.get_extra_info("peername")
    try:
        target_reader, target_writer = await asyncio.open_connection(target_ip, target_port)
        await asyncio.gather(pipe(client_reader, target_writer), pipe(target_reader, client_writer))
        target_writer.close()
        client_writer.close()
        await target_writer.wait_closed()
        await client_writer.wait_closed()
    except (ConnectionError, OSError) as exc:
        print(f"{peer} -> {target_ip}:{target_port}: {exc}", file=sys.stderr, flush=True)
        client_writer.close()


async def main(host_serial: str, helper_serial: str, should_join: bool) -> None:
    host_ip = phone_usb_ip(host_serial)
    helper_ip = phone_usb_ip(helper_serial)
    host_laptop_ip = laptop_usb_ip(host_ip)
    helper_laptop_ip = laptop_usb_ip(helper_ip)

    relays = [(helper_laptop_ip, 7070, host_ip, 7070)]
    relays.extend((host_laptop_ip, port, helper_ip, port) for port in RPC_PORTS)
    servers = []
    for listen_ip, listen_port, target_ip, target_port in relays:
        server = await asyncio.start_server(
            lambda r, w, ip=target_ip, p=target_port: forward(r, w, ip, p),
            listen_ip, listen_port, reuse_address=True,
        )
        servers.append(server)
        print(f"{listen_ip}:{listen_port} -> {target_ip}:{target_port}", flush=True)

    print(f"USB relay active; Host={host_ip}, Helper={helper_ip}", flush=True)
    if should_join:
        await asyncio.sleep(0.25)
        join_helper(host_serial, helper_serial)
        print("Asked the Helper app to join through its USB tether link.", flush=True)
    async with asyncio.TaskGroup() as group:
        for server in servers:
            group.create_task(server.serve_forever())


def join_helper(host_serial: str, helper_serial: str) -> None:
    raw = run("adb", "-s", host_serial, "shell", "cat /sdcard/Android/data/ai.maynards.mesh/files/invite.json")
    invite = json.loads(raw)
    invite["hosts"] = [laptop_usb_ip(phone_usb_ip(helper_serial))]
    command = "am start -n ai.maynards.mesh/.MainActivity --es role HELPER --es join " + shlex.quote(json.dumps(invite))
    run("adb", "-s", helper_serial, "shell", command)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("host_serial")
    parser.add_argument("helper_serial")
    parser.add_argument("--join-helper", action="store_true", help="join Helper through the USB relay")
    args = parser.parse_args()
    try:
        asyncio.run(main(args.host_serial, args.helper_serial, args.join_helper))
    except (RuntimeError, subprocess.CalledProcessError, OSError) as exc:
        print(f"USB relay: {exc}", file=sys.stderr)
        sys.exit(1)
