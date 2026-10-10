#!/usr/bin/env python3
"""
Transparent TCP Relay for Remote Mesh Devices (Part 2).
Enables devices behind NAT / mobile data to join the mesh without port forwarding.

Protocol:
1. Client connects to Relay TCP port (default 7071).
2. Sends JSON registration line:
   {"t": "register", "role": "host"|"helper", "mesh": "<id>", "token": "<token>", "channel": "control"|"rpc"}
3. When both host and helper register for the same (mesh, token, channel) key:
   Relay sends {"t": "spliced"} to both and pipes raw bytes bidirectionally.
"""

import argparse
import asyncio
import json
import logging
import sys

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("mesh-relay")

class RelayServer:
    def __init__(self):
        # Key: (mesh, token, channel) -> (reader, writer, asyncio.Future)
        self.waiting_hosts = {}
        self.waiting_helpers = {}
        self.lock = asyncio.Lock()

    async def pipe(self, src_reader: asyncio.StreamReader, dst_writer: asyncio.StreamWriter, label: str):
        try:
            while True:
                data = await src_reader.read(65536)
                if not data:
                    break
                dst_writer.write(data)
                await dst_writer.drain()
        except (asyncio.CancelledError, ConnectionResetError, BrokenPipeError):
            pass
        except Exception as e:
            logger.debug(f"Pipe {label} error: {e}")
        finally:
            try:
                dst_writer.close()
                await dst_writer.wait_closed()
            except Exception:
                pass

    async def handle_connection(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
        addr = writer.get_extra_info("peername")
        logger.debug(f"Incoming connection from {addr}")

        try:
            line = await asyncio.wait_for(reader.readline(), timeout=15.0)
            if not line:
                writer.close()
                return

            req = json.loads(line.decode("utf-8").strip())
            if req.get("t") != "register":
                writer.write(b'{"t":"error","reason":"invalid registration"}\n')
                await writer.drain()
                writer.close()
                return

            role = req.get("role")
            mesh = req.get("mesh", "")
            token = req.get("token", "")
            channel = req.get("channel", "control")
            key = (mesh, token, channel)

            logger.info(f"Registered {role} from {addr} for key {key}")

            match_reader = None
            match_writer = None

            async with self.lock:
                if role == "host":
                    if key in self.waiting_helpers:
                        match_reader, match_writer = self.waiting_helpers.pop(key)
                    else:
                        self.waiting_hosts[key] = (reader, writer)
                elif role == "helper":
                    if key in self.waiting_hosts:
                        match_reader, match_writer = self.waiting_hosts.pop(key)
                    else:
                        self.waiting_helpers[key] = (reader, writer)
                else:
                    writer.write(b'{"t":"error","reason":"unknown role"}\n')
                    await writer.drain()
                    writer.close()
                    return

            if match_reader and match_writer:
                logger.info(f"Splicing connection for {key}")
                # Both connected! Send acknowledgment
                ack = b'{"t":"spliced"}\n'
                writer.write(ack)
                match_writer.write(ack)
                await asyncio.gather(writer.drain(), match_writer.drain(), return_exceptions=True)

                # Launch bidirectional pipes
                t1 = asyncio.create_task(self.pipe(reader, match_writer, f"{role}->peer"))
                t2 = asyncio.create_task(self.pipe(match_reader, writer, f"peer->{role}"))
                await asyncio.gather(t1, t2, return_exceptions=True)
                logger.info(f"Session closed for {key}")
            else:
                # Wait until matched, disconnected, or timeout (120s)
                wait_start = asyncio.get_event_loop().time()
                while True:
                    await asyncio.sleep(1.0)
                    if writer.is_closing() or (asyncio.get_event_loop().time() - wait_start > 120.0):
                        break
                    # Verify we are still in waiting dict
                    async with self.lock:
                        pool = self.waiting_hosts if role == "host" else self.waiting_helpers
                        if key not in pool:
                            # We were matched by another incoming task
                            break

        except (asyncio.TimeoutError, ConnectionResetError, BrokenPipeError):
            pass
        except Exception as e:
            logger.error(f"Error handling connection from {addr}: {e}")
        finally:
            async with self.lock:
                self.waiting_hosts.pop(key, None)
                self.waiting_helpers.pop(key, None)
            try:
                writer.close()
                await writer.wait_closed()
            except Exception:
                pass

async def main():
    parser = argparse.ArgumentParser(description="Mesh Transparent TCP Relay")
    parser.add_argument("--bind", default="0.0.0.0", help="Bind IP address")
    parser.add_argument("--port", type=int, default=7071, help="TCP port to listen on")
    args = parser.parse_args()

    relay = RelayServer()
    server = await asyncio.start_server(relay.handle_connection, args.bind, args.port)
    logger.info(f"Relay listening on {args.bind}:{args.port}")

    async with server:
        await server.serve_forever()

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
