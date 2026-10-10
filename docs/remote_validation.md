# MeshAI local WebRTC RPC validation

Status: **verified** — iQOO 15 Host completed a Qwen3.5-9B request with the Poco F5 Helper participating through the local WebRTC RPC bridge. Automatic split preference remains disabled. This is local-network validation only; no public TURN/signaling deployment or cross-network test was performed.

## Test setup

- Host: iQOO 15 (`10BFBJ0SQJ001GG`, local Wi-Fi address `192.168.112.146`).
- Helper: Poco F5 (`e1074ce7`, local Wi-Fi address `192.168.113.46`).
- Laptop: ran the local signaling service and collected logs; it did not execute model layers.
- Model: Qwen3.5-9B Q4_K_M, 5,780,091,008 bytes present on both phones.
- Controlled split: Host layers 0–26; Helper layers 27–31. This was explicitly forced for validation, not selected by the normal automatic planner.
- Helper RPC server and Host llama.cpp runtime both reported version 7.0.0.

## Implementation changes in this pass

- Bound the Host RPC proxy to IPv4 loopback (`127.0.0.1`), matching the address passed to llama.cpp.
- Changed from one DataChannel for every native TCP socket to one ordered/reliable `rpc:n` DataChannel per accepted TCP connection. Native RPC opens multiple short-lived connections during setup and additional connection(s) for model work.
- Added on-demand Helper-side local TCP connections to its loopback `ggml-rpc-server` for each RPC DataChannel.
- Kept the byte-stream adapter's 16 KiB maximum data chunks, 4 MiB inbound queue per stream, 1 MiB acknowledged send window, sequence checks, ACKs, and timeout/error reporting.
- Copied received WebRTC buffers synchronously inside the callback. Each send uses a newly allocated frame buffer; the call to `DataChannel.send` returns before the local buffer goes out of scope.
- Fixed a lifecycle bug where the listener task's `finally` block closed the TCP socket and DataChannel immediately after asynchronous pump setup returned. Socket/channel cleanup now belongs to the per-connection pump lifecycle.
- Gave each active TCP connection two I/O workers so one blocked direction cannot starve the reverse direction. Up to three accepted RPC sockets are supported concurrently.
- Kept ordinary split preference disabled; only the controlled device test forced the split.

## Verified WebRTC RPC result

On 2026-10-11, the Host and Helper paired over the local Wi-Fi path. The Host's localhost RPC proxy forwarded native llama.cpp RPC traffic through per-socket DataChannels to the Poco's local RPC server.

Evidence from the Poco's native `MeshEngine` log:

- Multiple accepted RPC sockets reported `[hello] version: 7.0.0`.
- Device buffer allocation completed, including a 565,924,096-byte model buffer and additional compute buffers.
- Native RPC processed repeated `set_tensor_hash` requests for large tensors.
- Native Helper logged `graph_compute` (`248` nodes, `319` tensors) and `graph_recompute` while loading/warming the model and again during the completion request.
- The Helper process was running the native `ggml-rpc-server`; the laptop did not execute model layers.

The Host reported `model loaded` approximately 51.4 seconds after the engine began loading. Through the Host's local `/v1/chat/completions` endpoint, the request returned HTTP 200 and exactly:

> remote RPC completion works

For a 20-token prompt and 5 generated tokens, this one WebRTC sample reported:

| Measure | WebRTC result |
| --- | ---: |
| Whole HTTP request | 8.48 s |
| Prompt evaluation | 7.06 s / 2.83 tokens/s |
| Generation | 1.39 s / 2.89 tokens/s |
| Total engine prompt + generation | 8.45 s |
| Separate TTFT | Not captured; request was non-streaming |
| ICE route / RTT / jitter | Local pairing succeeded; this inference run's candidate-pair and RTT snapshot was not retained |
| RPC payload byte total / throughput | Not measured across the whole inference session |

This proves inference, but one sample is not a performance conclusion.

## Direct TCP comparison

The same two phones, Qwen3.5-9B model, and forced 0–26 / 27–31 split were previously tested over the existing direct TCP mode. The completion returned `distributed RPC completion works`. In that sample, model load took about 37.88 seconds; a 20-token prompt took 11.23 seconds (1.78 tokens/s); 5 generated tokens took 1.48 seconds (2.70 tokens/s); total prompt plus generation was 12.71 seconds.

The WebRTC and direct-TCP prompts requested different exact phrases, and the readings are single samples. They show that WebRTC inference works; they are not a controlled benchmark. A repeated same-prompt, same-thermal-state benchmark with TTFT, end-to-end RPC byte counters, throughput, and candidate-pair RTT is still needed before comparing performance.

## Tensor cache status

Before the WebRTC model run, the Helper cache preparer reported 20 large tensor files already present, about 609,878,016 bytes total. During native RPC model loading, the Helper logged `set_tensor_hash` operations for large tensors (including 27,525,120-, 28,311,552-, and 41,287,680-byte entries). This is consistent with llama.cpp's hash-based RPC tensor cache path being used, so the model's large weights were not simply uploaded anew as ordinary `set_tensor` payloads.

The available log does not expose a per-hash hit/miss counter from the native RPC server. Therefore the app's local cache inventory and the native hash operations are confirmed, while a byte-accurate proof of avoided transfer for each individual hash remains uncollected. Small tensors that do not use the hash path may still be transferred normally.

## Helper resources and compatibility

- Poco reported 7,354,340 kB total physical memory and about 1,933,232 kB `MemAvailable` after the inference run. Its app process PSS at that snapshot was about 183,584 kB; the native RPC helper was a separate process. Memory figures vary with Android reclaim and other apps.
- The Helper's native CPU RPC backend started successfully, accepted the Host's v7 protocol, allocated model/compute buffers, and executed graph operations. The assigned Qwen layers therefore proved compatible with this device/build for this run.
- This run did not validate a smaller fallback model, GPU backend, cold-cache transfer volume, memory pressure under sustained inference, or thermal stability.

## Stream handling audit

- **Ordering and delivery:** DataChannels are configured ordered and reliable. The adapter also numbers data frames and checks exact next-byte offsets. It does not attempt to resume a lost RPC stream; a channel failure aborts the underlying TCP connection.
- **Chunking and bounded flow:** Writes are split at 16 KiB. Sender waits for buffered amount and a 1 MiB byte-acknowledged window; incoming queued data is capped at 4 MiB per channel. ACKs advance only as the TCP consumer reads bytes, providing backpressure before the inbound bound is reached for a compliant peer.
- **ByteBuffer lifetime:** inbound data is copied before the WebRTC callback returns. Outbound frames own their allocated ByteBuffer through `send`.
- **Partial TCP writes:** the bridge uses `OutputStream.write(byte[], off, len)`, whose contract writes the requested bytes or throws; TCP reads may be short and are forwarded as-is without assuming packet boundaries.
- **Closures and errors:** EOF, channel state changes, send rejection, framing/sequence errors, and timeouts terminate the stream and close the paired socket. A half-close is not preserved: EOF in either direction closes both sides. This matches observed llama.cpp RPC request lifetimes, but should be revisited if a future RPC client requires TCP half-close semantics.
- **Concurrent connections:** each TCP socket gets its own channel and two pump threads; the acceptor rejects connections above the configured limit of three. The observed model load opened multiple setup channels and kept the active RPC path long enough to perform graph work.
- **Integrity comparison:** prior binary probing verified bidirectional 8 MiB payloads on an earlier adapter build. The current per-RPC-connection adapter completed the native RPC handshake and inference, but a fresh 8 MiB SHA-256 probe was not run after the latest lifecycle fix. Thus native RPC correctness is verified, while current-build large-payload probe equivalence remains a follow-up.

## Earlier failures and fix

Earlier attempts failed at distinct adapter stages:

1. One DataChannel was reused across multiple native TCP connections. Closing the first TCP socket closed the shared RPC channel, and llama.cpp reported a malformed/failed RPC response.
2. After switching to one channel per TCP connection, an accept-task cleanup block still ran when asynchronous bridge setup returned. It prematurely closed the accepted socket/channel; logs showed zero bytes.
3. The cleanup was moved to the per-connection pump lifecycle, and independent bidirectional I/O workers were retained. The latest run then completed native handshake, helper tensor/buffer operations, graph execution, and a user-visible model completion.

## Still pending before public cross-network validation

- Re-run the current-build 8 MiB SHA-256 bidirectional probe and the exact same prompt in direct TCP and WebRTC modes, capturing TTFT, RPC total bytes/throughput, and ICE RTT/jitter.
- Repeat after a cold process start and record peak memory/thermal state.
- Only after those local measurements, deploy authenticated public signaling/TURN and validate Wi-Fi-to-cellular and forced relay. No public deployment was performed here.

References: [llama.cpp RPC documentation](https://github.com/ggml-org/llama.cpp/blob/master/tools/rpc/README.md), [RFC 8831](https://www.rfc-editor.org/rfc/rfc8831.html), and [WebRTC Android DataChannel API](https://webrtc.googlesource.com/src/+/448691c8b9332fcfe483dc7e3a5e26b9e73dfa78/sdk/android/api/org/webrtc/DataChannel.java).
