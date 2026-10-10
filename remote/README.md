# MeshAI public remote transport

This directory contains the first server-side piece of cross-network transport:
short-lived pairing/signaling sessions and coturn configuration. It does not run
models and does not replace llama.cpp RPC. WebRTC DataChannels are intended to
carry the unchanged RPC byte streams end to end.

## Components

- `server.mjs`: creates a 10-minute one-helper session, authenticates both peers
  with separate random tokens, forwards bounded SDP/ICE messages, and issues
  short-lived coturn credentials.
- `coturn`: optional STUN/TURN server for candidate discovery and relaying.
- `caddy`: HTTPS/WSS termination for the signaling service.

Signaling payloads are connection metadata. Model RPC payloads travel over the
phones' DTLS-protected WebRTC DataChannel and are not forwarded by this service.
The session and TURN credentials are in-memory and expire; this first version
does not offer user accounts or persistent device identities.

## Deploy on a public Linux host

1. Point `MESH_DOMAIN` and `TURN_REALM` at the host. Open TCP 80/443 for Caddy,
   UDP/TCP 3478 for STUN/TURN, TCP 5349 for TURN/TLS, and UDP 49160–49200 for
   TURN relay traffic. If the server is behind NAT, configure its public address
   in coturn's `--external-ip` setting and forward the full relay port range.
2. Copy `.env.example` to `.env` and set `TURN_SECRET` to a fresh random value
   of at least 32 bytes. Use the same secret for coturn and the signaling service.
3. Put a certificate and private key for the TURN hostname in
   `turn-certs/fullchain.pem` and `turn-certs/privkey.pem` (mode 0600 for the
   private key). Caddy obtains and renews the HTTPS/WSS certificate itself.
4. Start the service with `docker compose --profile turn up -d --build`.
5. Confirm `https://$MESH_DOMAIN/health` returns `{"ok":true}`. Confirm UDP
   relay ports are reachable before attempting a forced-relay test.

Do not expose the llama.cpp RPC listener itself on a public interface. The app
must keep it on loopback behind the WebRTC-to-TCP adapter. Do not enable request
body or WebSocket query logging at the public proxy; the pairing tokens are
short-lived bearer secrets.

## Protocol sketch

- `POST /v1/sessions` returns `sessionId`, `hostToken`, `joinToken`, and expiry.
- `GET /v1/turn?session=…&role=…&token=…` returns one-hour TURN credentials.
- `wss://…/v1/signal?session=…&role=…&token=…` relays `offer`, `answer`, `ice`,
  and `hangup` JSON messages between exactly one host and one helper.

The Android app still needs to call these endpoints, create the PeerConnection,
and bridge its DataChannels to the existing control and RPC TCP sockets. Until
that app integration and a public deployment are available, this service alone
does not make the phones remotely runnable.

## Local checks

```sh
npm install
PORT=3000 node server.mjs
curl -i http://127.0.0.1:3000/health
```
