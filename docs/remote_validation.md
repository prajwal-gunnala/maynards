# MeshAI remote RPC validation

Status: **WAN validation blocked before deployment.** Local WebRTC inference and the current-build 8 MiB integrity probe are verified. Five new 128-token direct-TCP runs also completed using a forced 24/8 Host/Helper split. A controlled direct-TCP-versus-WebRTC comparison is still unverified because the WebRTC benchmark used a different prompt and split. The public OCI endpoint refuses external TCP 443 even though Caddy is listening on the VM and its host firewall accepts 443. The VM's OCI instance principal cannot read the VNIC/ingress configuration (`NotAuthorizedOrNotFound`), so the cloud ingress rule cannot be verified or changed from this session. No public MeshAI service was deployed.

Automatic split preference remains disabled.

## Final status by requirement (2026-10-11)

### VERIFIED

- Baseline source commit: `43c994640e1ccd13492d45a950fc28292df6248b` (`feat(mesh): checkpoint local WebRTC RPC transport`).
- Baseline debug APK SHA-256: `45a78103844301cfba7f00335f57d5bf5966b6aff88f46f35af5b9594473d089`.
- Follow-up background-use APK SHA-256: `4df39d8eb599fb0311ba8dd0ee20923e4846de387cd94a7ed76921b2343239a4`. It removes the Activity's screen-on flag so the display can sleep; installed on both phones. A short request completed after the Host display was put to sleep, with the foreground service's `mesh:engine` partial wake lock held. This verifies one screen-off inference, not long-duration OEM background survival.
- Qwen3.5-9B Q4_K_M model SHA-256 on both phones: `ef6f244d63abcba4f8d30b7cbd9369e9e798fc33cf21109889373f59af329c93` (5,780,091,008 bytes).
- Controlled local split: iQOO Host layers 0–26; Poco F5 Helper layers 27–31. Helper is using the native CPU RPC backend; both sides reported RPC protocol v7.0.0. The Helper logged `graph_compute` and `graph_recompute` during a real completion.
- A current-build bidirectional 8 MiB SHA-256 probe passed over WebRTC. The Host UI reported `8 MiB each direction · SHA-256 verified`; measured rate on the latest run was `0.47 MiB/s` (an earlier run showed 1.44 MiB/s, so the link rate varied substantially).
- Same-prompt 128-token local WebRTC benchmark completed through the Host API: HTTP 200, 99 prompt tokens, 128 generated tokens, TTFT 12.85 s, prompt evaluation 7.74 tokens/s, decode 2.22 tokens/s, total 69.98 s. The output was valid and stopped at the requested token limit. This is one run, not a stable performance estimate.
- Screen-off Host request completed: HTTP 200, 30 prompt tokens, 16 generated tokens, TTFT 13.23 s, prompt evaluation 4.18 tokens/s, decode 2.48 tokens/s. The service held `mesh:engine`; Helper logs showed graph recomputation during the request.
- Current stream adapter bounds and cleanup are audited below. No runtime or model changes were made for remote deployment.
- The OCI Compose configuration parses successfully with the Coturn profile, and the Caddy snippet was validated against the VM's installed Caddy. Neither service was started.
- The MeshAI Android foreground service is already used for Host/Helper operation, with a partial CPU wake lock and Wi-Fi lock while active. Battery exemption and OEM auto-start still require the phone owner to enable them in Android settings.

### FAILED / BLOCKED

- External `curl https://140.245.200.250` and TCP connect to port 443 returned **connection refused**. On the VM, Caddy's Docker proxy listens on TCP 443, and the Linux INPUT chain accepts TCP 443; therefore the remaining ingress path is outside the service container and host INPUT rule. OCI VNIC inspection failed with `NotAuthorizedOrNotFound` for the VM's instance principal.
- A controlled same-prompt direct-TCP versus WebRTC comparison is still incomplete. The five-run direct-TCP sample below is distributed, but it does not match the earlier WebRTC prompt or layer allocation.

### UNTESTED

- Public HTTPS/WSS signaling and TURN deployment; no new MeshAI service was exposed.
- Wi-Fi-to-cellular ICE establishment, selected candidate pair, WAN binary integrity, WAN Qwen inference, and forced TURN-only inference.
- WAN RTT/jitter, throughput, TTFT, prefill/decode, bytes, reconnect behavior, and remote Helper `graph_compute` logs.
- OCI ingress rules for TCP 443, UDP/TCP 3478, and UDP/TCP 49160–49200. The OCI CLI identity available on the VM lacks VNIC inspection permissions.
- Current-build identical direct-TCP/WebRTC benchmark. One short screen-off inference is verified, but sustained background operation, long idle survival, OEM battery restrictions, and repeated process recovery remain untested.

To unblock deployment, the OCI administrator must allow inbound TCP 443, UDP and TCP 3478, and UDP and TCP 49160–49200 on the VM's subnet security list or attached NSG, then verify TCP 443 is reachable from the public internet. Before public validation, complete a controlled direct-TCP versus WebRTC split benchmark with the same prompt, split and output length.

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

## Current-build WebRTC benchmark

The same 427-character technical prompt was sent over the current local WebRTC split with deterministic settings (`temperature=0`, seed 1729, `max_tokens=128`, `ignore_eos=true`). The result was HTTP 200, 99 prompt tokens, and 128 generated tokens:

| Measure | Current WebRTC sample |
| --- | ---: |
| First visible token (TTFT) | 12.85 s |
| Prompt evaluation | 12.79 s / 7.74 tokens/s |
| Generation | 57.12 s / 2.22 tokens/s |
| End-to-end request | 69.98 s |
| Route | Local direct ICE; not WAN or TURN |

The output was valid but truncated at the requested 128-token cap. This is the only current-build 128-token sample. A short screen-off request also completed at 2.48 generated tokens/s; it does not establish long-duration background reliability.

## Direct TCP comparison

The same two phones, Qwen3.5-9B model, and forced 0–26 / 27–31 split were previously tested over direct TCP. That historical completion returned `distributed RPC completion works`; model load took about 37.88 seconds, a 20-token prompt took 11.23 seconds (1.78 tokens/s), 5 generated tokens took 1.48 seconds (2.70 tokens/s), and total prompt plus generation took 12.71 seconds. It used a different prompt and output length, so it is not directly comparable with the WebRTC or five-run samples.

### Five-run direct-TCP sample (2026-10-11)

The connected iQOO Host and Poco Helper were benchmarked over the existing direct TCP RPC path. The Host command used `--rpc 192.168.113.46:50062 -ngl 9`, which assigns eight final transformer layers (24–31) to the Helper and keeps the output head on the Host. Both engines used their packaged CPU backends; the Helper's native RPC logs showed repeated `graph_recompute` operations during the requests. This is a real distributed run, not a planner-only allocation.

The app's owner-set memory caps were 6 GB on the iQOO and 4 GB on the Poco. The active plan used Host layers 0–23 and Helper layers 24–31; the Host UI showed 11 ms link RTT during the run. I did not raise the iQOO cap to force a single-device run.

Five identical streaming requests used Qwen3.5-9B Q4_K_M, a fixed 112-token prompt, temperature 0, seed 1729, and 128 generated tokens. Results from the Host endpoint:

> We are benchmarking local distributed inference on two Android phones. Explain how a model split between a host and a helper executes one generated token at a time. The host processes its assigned transformer layers, sends intermediate activations to the helper over RPC, receives the result, then computes the output token. Describe the difference between model weights transferred during setup and activations exchanged during inference. Keep the explanation precise, mention that layer placement and network latency affect speed, and do not claim the helper receives the original prompt text.

| Run | TTFT (s) | Prompt eval (tok/s) | Decode (tok/s) | Endpoint wall time (s) | Cached prompt tokens |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 38.69 | 2.90 | 2.28 | 94.45 | 0 |
| 2 | 47.78 | 0.14* | 1.75 | 120.25 | 108 |
| 3 | 39.36 | 0.10* | 2.48 | 90.62 | 108 |
| 4 | 44.71 | 0.09* | 2.64 | 92.82 | 108 |
| 5 | 46.36 | 0.09* | 2.52 | 96.79 | 108 |

Mean decode was 2.33 tok/s (median 2.48 tok/s); mean endpoint wall time was 98.99 s. The prompt cache changed after run 1, so prompt-evaluation figures marked `*` cover only four uncached suffix tokens and must not be compared with run 1. The stream's TTFT and server prompt timing also diverged by up to about 18 seconds in cached runs, so that discrepancy needs investigation before TTFT is used to attribute a bottleneck.

The installed debug APK SHA-256 was `0b26daa60e084cdbfba422387b6b8c10fa087c125668493b77ee4c6f861d4d12`; source commit was `43c994640e1ccd13492d45a950fc28292df6248b`; model hash is recorded above. During the post-run snapshot, the Host engine process RSS was about 7.0 GiB with 5.9 GiB system memory available; the Helper RPC process RSS was about 0.96 GiB with 1.8 GiB available. Android thermal status was nominal/light, with skin temperatures near 40°C.

The APK includes `libggml-cpu.so` and `libggml-rpc.so` but no separate OpenCL or Vulkan backend library. The tested invocation used no GPU device selection, and actual execution was CPU-only. GPU compatibility/performance is therefore **untested**, not supported by this run. No per-device graph durations, RPC byte totals, socket wait time, or bridge queue durations were exposed by the current instrumentation, so this sample cannot identify how much decode time is compute versus RPC synchronization.

This five-run series is the current direct-TCP baseline. It is not a fair transport comparison with the prior WebRTC sample (2.22 tok/s), which used a different prompt and a 27/5 split. Whole-model iQOO-only (over the selected 6 GB cap) and alternative layer allocations were not tested in this series.

## Tensor cache status

Before the WebRTC model run, the Helper cache preparer reported 20 large tensor files already present, about 609,878,016 bytes total. During native RPC model loading, the Helper logged `set_tensor_hash` operations for large tensors (including 27,525,120-, 28,311,552-, and 41,287,680-byte entries). This is consistent with llama.cpp's hash-based RPC tensor cache path being used, so the model's large weights were not simply uploaded anew as ordinary `set_tensor` payloads.

The available log does not expose a per-hash hit/miss counter from the native RPC server. Therefore the app's local cache inventory and the native hash operations are confirmed, while a byte-accurate proof of avoided transfer for each individual hash remains uncollected. Small tensors that do not use the hash path may still be transferred normally.

## Helper resources and compatibility

- Poco reported 7,354,340 kB total physical memory and about 1,933,232 kB `MemAvailable` after the earlier split inference. In the latest direct pairing, the UI reported 2.7 GB free and 1.6 GB available for the model. Its MeshAI app process PSS was about 180–230 MB in the snapshots; the native RPC helper is a separate process. Memory figures vary with Android reclaim and other apps.
- The Helper's native CPU RPC backend started successfully, accepted the Host's v7 protocol, allocated model/compute buffers, and executed graph operations. The assigned Qwen layers therefore proved compatible with this device/build for this run.
- This run did not validate a smaller fallback model, GPU backend, cold-cache transfer volume, or long-duration thermal stability. The five-run direct-TCP sample below provides a short sustained snapshot only.

## Stream handling audit

- **Ordering and delivery:** DataChannels are configured ordered and reliable. The adapter also numbers data frames and checks exact next-byte offsets. It does not attempt to resume a lost RPC stream; a channel failure aborts the underlying TCP connection.
- **Chunking and bounded flow:** Writes are split at 16 KiB. Sender waits for buffered amount and a 1 MiB byte-acknowledged window; incoming queued data is capped at 4 MiB per channel. ACKs advance only as the TCP consumer reads bytes, providing backpressure before the inbound bound is reached for a compliant peer.
- **ByteBuffer lifetime:** inbound data is copied before the WebRTC callback returns. Outbound frames own their allocated ByteBuffer through `send`.
- **Partial TCP writes:** the bridge uses `OutputStream.write(byte[], off, len)`, whose contract writes the requested bytes or throws; TCP reads may be short and are forwarded as-is without assuming packet boundaries.
- **Closures and errors:** EOF, channel state changes, send rejection, framing/sequence errors, and timeouts terminate the stream and close the paired socket. A half-close is not preserved: EOF in either direction closes both sides. This matches observed llama.cpp RPC request lifetimes, but should be revisited if a future RPC client requires TCP half-close semantics.
- **Concurrent connections:** each TCP socket gets its own channel and two pump threads; the acceptor rejects connections above the configured limit of three. The observed model load opened multiple setup channels and kept the active RPC path long enough to perform graph work.
- **Integrity comparison:** the current per-RPC-connection adapter passed an 8 MiB SHA-256 transfer in both directions after the lifecycle fix, and also completed native RPC handshake and inference. The latest probe measured 0.47 MiB/s; this is a single noisy sample, not a sustained throughput benchmark.

## Earlier failures and fix

Earlier attempts failed at distinct adapter stages:

1. One DataChannel was reused across multiple native TCP connections. Closing the first TCP socket closed the shared RPC channel, and llama.cpp reported a malformed/failed RPC response.
2. After switching to one channel per TCP connection, an accept-task cleanup block still ran when asynchronous bridge setup returned. It prematurely closed the accepted socket/channel; logs showed zero bytes.
3. The cleanup was moved to the per-connection pump lifecycle, and independent bidirectional I/O workers were retained. The latest run then completed native handshake, helper tensor/buffer operations, graph execution, and a user-visible model completion.

## Still pending before public cross-network validation

- Re-run the exact same prompt in a confirmed direct-TCP split and WebRTC split, capturing TTFT, RPC total bytes/throughput, and link RTT/jitter. The current-build 8 MiB WebRTC SHA-256 probe has passed.
- Repeat after a cold process start and record peak memory/thermal state.
- Only after those local measurements, deploy authenticated public signaling/TURN and validate Wi-Fi-to-cellular and forced relay. No public deployment was performed here.

References: [llama.cpp RPC documentation](https://github.com/ggml-org/llama.cpp/blob/master/tools/rpc/README.md), [RFC 8831](https://www.rfc-editor.org/rfc/rfc8831.html), and [WebRTC Android DataChannel API](https://webrtc.googlesource.com/src/+/448691c8b9332fcfe483dc7e3a5e26b9e73dfa78/sdk/android/api/org/webrtc/DataChannel.java).
