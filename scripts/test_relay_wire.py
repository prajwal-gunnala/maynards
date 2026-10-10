#!/usr/bin/env python3
"""
Automated test suite for Part 2: Transparent Relay.
Tests relay registration, key matching, spliced acknowledgment, and bidirectional payload piping.
"""

import json
import socket
import subprocess
import sys
import time
import unittest

RELAY_PORT = 7999

class TestRelayWire(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # Start relay server on test port
        cls.proc = subprocess.Popen(
            [sys.executable, "scripts/relay.py", "--port", str(RELAY_PORT), "--bind", "127.0.0.1"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )
        for _ in range(50):
            try:
                with socket.create_connection(("127.0.0.1", RELAY_PORT), timeout=0.2):
                    break
            except Exception:
                time.sleep(0.1)

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        try:
            cls.proc.wait(timeout=2.0)
        except subprocess.TimeoutExpired:
            cls.proc.kill()

    def test_relay_splice_and_bidirectional_traffic(self):
        # 1. Connect Host socket
        s_host = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s_host.connect(("127.0.0.1", RELAY_PORT))

        host_reg = {
            "t": "register",
            "role": "host",
            "mesh": "mesh-alpha",
            "token": "tok-42",
            "channel": "control"
        }
        s_host.sendall((json.dumps(host_reg) + "\n").encode())

        # 2. Connect Helper socket
        s_helper = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s_helper.connect(("127.0.0.1", RELAY_PORT))

        helper_reg = {
            "t": "register",
            "role": "helper",
            "mesh": "mesh-alpha",
            "token": "tok-42",
            "channel": "control"
        }
        s_helper.sendall((json.dumps(helper_reg) + "\n").encode())

        # 3. Both should receive {"t": "spliced"}
        resp_host = json.loads(s_host.makefile().readline().strip())
        resp_helper = json.loads(s_helper.makefile().readline().strip())

        self.assertEqual(resp_host.get("t"), "spliced")
        self.assertEqual(resp_helper.get("t"), "spliced")

        # 4. Helper sends hello payload to Host
        hello_payload = {"t": "hello", "id": "iqoo15-remote", "specs": {"name": "iQOO 15"}}
        s_helper.sendall((json.dumps(hello_payload) + "\n").encode())

        received_by_host = json.loads(s_host.makefile().readline().strip())
        self.assertEqual(received_by_host.get("t"), "hello")
        self.assertEqual(received_by_host.get("id"), "iqoo15-remote")

        # 5. Host sends Ned Capsule to Helper
        capsule_payload = {"t": "capsule", "capsule_id": "cap-remote-100", "task": "agent"}
        s_host.sendall((json.dumps(capsule_payload) + "\n").encode())

        received_by_helper = json.loads(s_helper.makefile().readline().strip())
        self.assertEqual(received_by_helper.get("t"), "capsule")
        self.assertEqual(received_by_helper.get("capsule_id"), "cap-remote-100")

        s_host.close()
        s_helper.close()

    def test_relay_token_mismatch_does_not_splice(self):
        s_host = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s_host.connect(("127.0.0.1", RELAY_PORT))
        s_host.settimeout(0.5)

        s_host.sendall((json.dumps({
            "t": "register", "role": "host", "mesh": "mesh-alpha", "token": "correct-tok", "channel": "control"
        }) + "\n").encode())

        s_helper = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s_helper.connect(("127.0.0.1", RELAY_PORT))
        s_helper.settimeout(0.5)

        s_helper.sendall((json.dumps({
            "t": "register", "role": "helper", "mesh": "mesh-alpha", "token": "wrong-tok", "channel": "control"
        }) + "\n").encode())

        # Neither should receive spliced immediately
        with self.assertRaises(socket.timeout):
            s_host.recv(1024)

        with self.assertRaises(socket.timeout):
            s_helper.recv(1024)

        s_host.close()
        s_helper.close()

if __name__ == "__main__":
    unittest.main()
