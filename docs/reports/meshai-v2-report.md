# MeshAI V2: technical, commercial and research report

Written 2026-10-11 by reading the repository, git history, measurement logs, the Mesh Compiler results
database (read-only) and the pinned llama.cpp source. Nothing was run on the phones and no code was changed.

| Item | Value |
|---|---|
| Repository | `/home/prajwal/Documents/GitHub/maynards`, branch `main`, read at `95fc708`; later Origami commits `00d53d9`, `42a4512`, `dd209f3` included in §3 and §8 from their commit bodies |
| Working tree | Has uncommitted edits by another agent (Case Room files, `docs/roadmap-next.md`, `docs/privacy-and-use-cases.md`, Origami 3D files, `remote/`). This report quotes the working-tree versions of those docs and says so where it matters. |
| Engine | llama.cpp `66fba63af1f4161052c33024d150cac31f46ff37` (2026-09-23), CPU backend, RPC protocol 7.0.0 (`ggml/include/ggml-rpc.h:9-11`), built by `scripts/build-llama.sh` |
| Mesh Compiler | Separate repo `github.com/prajwal-gunnala/mesh-compiler` (docs copied at `e21545b` into `docs/mesh-compiler/`); results DB `/mnt/storage/meshai/tuner/runs.sqlite`: 122 runs (#1–#122), 120 ok, all Qwen3-4B-Instruct-2507, 2026-10-10 01:04–03:55 |
| External URLs | Accessed 2026-10-11; pricing URLs in the references were gathered before the scope change and are for the separate market report |

## Labels used throughout

- **VERIFIED**: a recorded run, a log, a unit test that exists in the tree, or source code read for this report.
- **PARTIAL**: implemented and partly checked, or checked only once or under narrow conditions.
- **FAILED**: tried and did not work, or the result was void.
- **PLANNED**: written down as a plan; no working code or no run.
- **ESTIMATE**: a number derived by arithmetic or taken from an assumption, not measured. Assumptions are stated.
- **MISSING**: the number is needed and does not exist anywhere in the sources.

---

## Executive summary (one page)

**What MeshAI V2 is.** An Android app (Kotlin, Compose) plus a Rust laptop agent that pools memory across
phones and a laptop so one large language model can run when no single device can hold it. One device, the
**Host**, runs `llama-server` with an OpenAI-compatible API on port 8080; **Helpers** run llama.cpp's
`ggml-rpc-server` and hold contiguous ranges of transformer layers. Weights are kept on each Helper in a
content-addressed cache so splits start without re-sending gigabytes. Inference itself is unmodified
llama.cpp (pinned `66fba63`, CPU only). The project's own code is the control plane: pairing, a memory planner,
a preflight check, a task router, signed per-Helper work orders ("Ned Capsules"), a thread-count tuner, a USB
relay, a WebRTC transport for the RPC byte stream, telemetry, and three applications (Legal Case Room, Code
room, Origami Lab).

**What is verified on real hardware.**
- Qwen3-Coder-30B-A3B Q4_K_M (18.56 GB, 48 layers) ran across a laptop and two iQOO 15 phones over USB
  tethering at **6.6–7.3 tok/s decode** (5 runs, 26–27 Sep), with the phones holding 13.74 GB (74%) in the
  01:25 run. It passed **15/15** coding problems (median 6.8 tok/s) and completed an Aider agent task
  end to end (7 min 34 s, 5/5 tests). Split readiness fell from **437 s to 70 s** once phones stored their
  layers locally (`docs/measurements.md:19-20`).
- On 11 Oct, Qwen3.5-9B Q4_K_M split 8:24 across two iQOO 15 phones over USB-only (laptop relays bytes):
  **7.5–7.6 tok/s decode**; prompt reading **18.6 tok/s** for 1,628 tokens, about **half** the single-phone
  rate (35.8 tok/s). This is the measured cost of splitting a model that would also fit on one phone
  (the Host was capped at 5 GB to force the split).
- Local WebRTC transport carried real llama.cpp RPC inference (iQOO + Poco F5), but at **2.22 tok/s**,
  one run, different prompt and split from the TCP baseline (2.33 mean, 5 runs). **No controlled
  TCP-vs-WebRTC comparison exists, and no WAN/TURN inference has run** (OCI ingress blocked).
- Splitting did not change answers on the one discriminating test: Qwen3-1.7B whole vs split failed the
  **same 8 of 15** problems (`results/REPORT.md`).

**What is not verified or failed.** WAN inference (blocked), GPU/NPU in the app (CPU only; the Mesh
Compiler's GPU wins are on a single phone and are *refused* by the app), any applied speed-up from the Mesh
Compiler (the only capsules that the app can apply are baselines; the on-phone Tune kept the default 6
threads), a pull-a-cable failure test, Origami fabrication, any compute-rental component (out of scope by
decision). One on-phone Tune run (~08:05, 11 Oct) was **void** because an old `llama-server` still held
port 8080 (fixed in `15962c6`).

**What is genuinely new vs integration.** The inference engine, RPC protocol, tensor-hash cache and
OpenAI API are llama.cpp's. MeshAI's distinct pieces are: (1) pre-filling the RPC tensor cache from each
phone's own model file (`LayerStore`), which turns a 437 s split start into 70 s; (2) a memory-proportional
contiguous-layer planner with preflight re-planning and an RTT/battery/memory gate; (3) a TCP-over-WebRTC
DataChannel bridge so unmodified llama.cpp RPC can traverse NAT; (4) HMAC-signed per-Helper work orders
with proposed-vs-applied reporting; (5) a task-time-scored, quality-gated settings tuner; (6) correctness
measured on the split model. None of (1)–(6) changes the per-token arithmetic; they are systems
integration and operations work, competently evidenced, on top of a known technique (pipeline split via
llama.cpp RPC), with prior art in prima.cpp, exo, distributed-llama, Petals and Pooled.

**Commercial reading.** Sections 6 and 7 are written separately (`docs/reports/meshai-v2-market.md`). Repo facts that bound any business case: one conversation slot per mesh (`-np 1`), 4.4–7.3 tok/s, wired links required (Wi-Fi/WAN failures), no metering, no authenticated RPC, compute rental out of scope by decision.

**Key contradictions found** (details in §4.9): the per-token hidden-state size used in docs and the app
(width × 2 bytes) is probably half the real F32 transfer, and the system's data path is a **star through the
Host**, not the phone-to-phone chain drawn in `docs/UNDERSTANDING.md` §2.5; the RTT used by the planner is the
**minimum** of the last 10 pings while code comments say "average"/"median"; the Mesh Compiler capsule task
times (95.7 s / 40.47 s) differ from the results table (45.4 s / 29.4–35.6 s) because they use the 2k-token
prompt runs; the Origami README still says "no kerf compensation / no structural rating" after both were added.

---

## Status summary table

| Area | Item | Status | Evidence |
|---|---|---|---|
| Engine | llama.cpp 66fba63 CPU build for Android arm64 (+dotprod+i8mm, 16 KB pages) and laptop | VERIFIED | `scripts/build-llama.sh`; libs in `android/app/src/main/jniLibs/arm64-v8a/` |
| Engine | GPU (OpenCL/Vulkan) or NPU in the app | PLANNED / out of scope | APK has only `libggml-cpu.so`, `libggml-rpc.so` (`docs/remote_validation.md:128`); `CLAUDE.md` puts NPU/GPU out of scope |
| Split | 30B across laptop + 2 phones over USB | VERIFIED | `docs/measurements.md:18-26` |
| Split | 9B across 2 phones, USB-only via laptop relay | VERIFIED | `docs/measurements.md:28-30`; commit `f5325ad` |
| Split | Wi-Fi split usable | FAILED (venue Wi-Fi) / PARTIAL (home LAN 2.2–2.5 tok/s) | `measurements.md:8,22`; `remote_validation.md:106-124` |
| Transport | WebRTC DataChannel RPC bridge, local ICE | PARTIAL (one 128-token run, one 5-token run, one screen-off run) | `docs/remote_validation.md:58-100` |
| Transport | WAN / TURN inference | FAILED (blocked: OCI ingress 443 refused) / untested | `docs/remote_validation.md:23-36` |
| Caching | Hash-addressed layer store, 437 s → 70 s | VERIFIED | `measurements.md:19-20`; `LayerStoreTest` |
| Planner | Never split if it fits; proportional split; RTT 60 ms / battery 20% / memory gates | VERIFIED (unit + live) | `brain/Planner.kt`; `PlannerTest`; commit `e539842` (4.80→7.26 tok/s) |
| Planner | Heat-aware planning | FAILED/REVERTED | `a64516d` reverted by `8869f2c` |
| Planner | Speed-aware placement from measured per-device profiles | PLANNED | roadmap stage 7 "speed-aware split" missing |
| Preflight | Re-plan or refuse at launch | PARTIAL (unit only) | `brain/Preflight.kt`; `PreflightTest`; roadmap stage 3 "phone check" missing |
| Router | Rule-based task detection + model choice | PARTIAL (seen once on phone: "reasoning → Qwen3.5 9B") | `TaskDetector.kt`, `Router.kt`; roadmap stage 4 |
| Multi-model chain | Case Room "Find conflicts" | PARTIAL (unit only) | `ConflictFinder.kt`, `CaseConflictsTest` |
| Compiler | Optuna search on one iQOO (Qwen3-4B), quality gate, prefix reuse | VERIFIED (single phone, separate repo) | `docs/mesh-compiler/03-results.md`; runs.sqlite |
| Compiler | Capsule export → app applies/refuses | PARTIAL (unit; capsules not on phones) | `189d853`; `CompilerCapsuleTest`; roadmap stage 7 |
| Compiler | Any measured speed-up active in the app | MISSING / not achieved | only baseline capsules are CPU-applicable; Tune kept default |
| Compiler | On-phone Tune (threads, ≥5 %) | VERIFIED once (kept 6 threads) / one FAILED (void) run | `measurements.md:29`; `15962c6`, `3690d12` |
| Compiler | Learning from use | PARTIAL (unit only; off by default) | `0d05deb`; `CompilerTest` |
| Compiler | AnswerCheck gate | PARTIAL (unit only; "not yet run on the phones") | `54d9364`; `AnswerCheckTest` |
| Capsules | Signed Ned Capsule per Helper, applied as proposed | VERIFIED once | `measurements.md:29`; `539002a` |
| Telemetry | Spider Sense from real events | PARTIAL | `Sense.kt`; `91db6d5` |
| Privacy | Bearer key, consent per Helper set, backup off | PARTIAL (code + consent on phone) | `docs/data-flow.md`; manifest `allowBackup="false"` |
| Privacy | Encrypted local TCP / authenticated RPC | MISSING | `docs/data-flow.md:233-236` |
| Failure | Dead engine / cut-off stream caught; one disclosed restart | PARTIAL (unit) | `b566c92`; "pull-a-cable test" missing |
| Apps | Chat, `mesh ask`, OpenAI API | VERIFIED | `measurements.md:13,17` |
| Apps | Aider coding agent on 30B mesh | VERIFIED (3 runs, 1 task) | `measurements.md:23-25` |
| Apps | Case Room import/search/cited Ask | PARTIAL (one split Ask, `[S01]` verified) | `measurements.md:28` |
| Apps | Case Room timeline/conflicts/progress UI | PARTIAL (unit only) | roadmap "phone-check" missing |
| Apps | Code room (read-only, file:line cites) | PARTIAL (one phone run each, single and split) | `measurements.md:27,30` |
| Apps | Origami generators, Filament 3D, structure check, kerf, wedged tenons, finger joints, joint zoom | PARTIAL (unit tests; 3D seen on a phone; crash fix checked on phone) | `origami/`; commits `3e81088`…`dd209f3` |
| Apps | Origami fabrication-validated | MISSING | every catalogue item "not fabrication-tested" |
| Business | Compute rental / marketplace / metering (see market report) | PLANNED (out of scope by decision); Antigravity metering branch audited as unsound | roadmap stage 10; `docs/branch-audit-2026-10-11.md:312` |
| FastPath | Activation-level stage exchange instead of graph RPC | PLANNED | `origami/final plan.md:144-156` |

---

## 1. Technical architecture

### 1.1 Components and roles

| Component | Language | Where | Role |
|---|---|---|---|
| MeshAI Android app | Kotlin/Compose, ~10.1 k lines in `android/app/src/main/kotlin` (incl. tests) | phones | Either **Host** (brain: planner, runner, `llama-server`, API, apps) or **Helper** (`ggml-rpc-server` + layer store + control client) |
| `mesh` agent | Rust, `agent/src/main.rs` (786 lines), `host.rs` (1,276), `host.html` (1,458) | laptop | `mesh join` (laptop as Helper), `mesh ask/review/tests/diff/hook` (CLI clients), `mesh host` (laptop as Host with web dashboard and its own planner, `host.rs:300-330`), `mesh panel`, `mesh route` (text/vision router) |
| Desktop app | Python `desktop/meshai.py`, `service.py`, Aider wrapper | laptop | Linux window over the dashboard; task runner driving Aider against the mesh API |
| Signalling service | Node 20, `remote/server.mjs`, `ws` 8.22.0 | intended for OCI VM | WebRTC session creation/offer/answer/ICE relay; issues coturn REST credentials (HMAC-SHA1, `server.mjs:58-72`); rate limit 20 req / 10 min / IP; 10 min session TTL; 64 KiB signal cap |
| coturn + Caddy | containers, `remote/compose.yaml`, `compose.oci.yaml` (uncommitted) | OCI VM | TURN relay (ports 3478, 5349, 49160–49200) and TLS front. **Not deployed** |
| Scripts | Python/bash, `scripts/` | laptop | build, USB relay, link watcher, benchmarks, capsule export, sustained-heat test |
| Origami Lab | Kotlin, `origami/app` (3,202 lines) | phone | standalone app whose sources are also compiled into the mesh app |

**Roles are not tied to device type**: a phone or the laptop can be Host or Helper
(`docs/UNDERSTANDING.md` §2.7; Rust `mesh host`/`mesh join`; Android RolePicker). VERIFIED for phone Host +
laptop Helper (`measurements.md:14`), laptop Host + phone Helpers (`:18-26`), phone Host + phone Helper
(`:28-30`).

### 1.2 Architecture diagram (as implemented, USB-only two-phone case of 11 Oct)

```
                 ┌───────────────────────────── LAPTOP (observer + byte relay, no model layers) ────────┐
                 │  scripts/phone-usb-relay.py: forwards Helper→Host :7070 (control) and              │
                 │  Host→Helper :50052/50062/50070/50080/50100 (RPC) between two USB-tether subnets    │
                 │  scripts/link-watch.py: reads /proc/net/{dev,tcp,tcp6} over adb (which iface)       │
                 │  `mesh ask --host` / dashboards (HTTP to :8080 with bearer key)                     │
                 └──────────────┬──────────────────────────────────────────────┬──────────────────────┘
                     rndis0 (USB tether)                               rndis0 (USB tether)
                                │                                              │
┌───────────── HOST PHONE (iQOO 15 …00XP) ─────────────┐      ┌────────── HELPER PHONE (iQOO 15 …01GG) ──────────┐
│ UI: Mesh · Models · Use(Chat, Case Room, Code,        │      │ MeshClient: JSON-lines control on :7070           │
│     Origami) · Stats                                  │      │   hello{id, token|secret, specs} → welcome{secret}│
│ MeshHost :7070 (pairing, specs every ~2 s, ping)      │◀────▶│   specs/pong/activity(graph_recompute)            │
│ Planner → Preflight → Runner (capsule per Helper,     │      │ CapsuleCheck (HMAC, expiry, device, range)        │
│   Compiler profile/Tune) → EngineArgs                 │      │ libmesh_rpc.so = ggml-rpc-server -H <iface IP>    │
│ libmesh_server.so = llama-server 0.0.0.0:8080         │      │   -p 50052 -t cores-2 -c (tensor cache)           │
│   (LLAMA_API_KEY bearer; app uses 127.0.0.1)          │─RPC─▶│   LLAMA_CACHE=files/cache → cache/rpc/<fnv64>     │
│   holds layers 0–7 + token_embd + output + norm       │◀─────│   holds layers 8–31 (Qwen3.5-9B)                  │
│ Case/Code text stays here (data-flow.md)              │      │ LayerStore pre-fills cache/rpc from own GGUF      │
└───────────────────────────────────────────────────────┘      └───────────────────────────────────────────────────┘
```

The 30B laptop-Host topology (26–27 Sep) is the same with the laptop running `llama-server` (Rust
`mesh host`) and each phone on its own USB tether directly to the laptop (no relay needed).

### 1.3 Model execution and layer splitting

**Execution** is llama.cpp's: the Host process owns the model file, builds the compute graph and schedules
sub-graphs onto backends; each Helper is a `ggml-rpc` backend device. `EngineArgs.host()`
(`brain/EngineArgs.kt:10-43`) builds the command line:

- always `-c <ctx> -t <threads> --jinja --fit off --reasoning off -np 1 -ctk q8_0 -ctv q8_0 -fa on -b 512`
  (capsule may override ctk/ctv/fa/b/ub);
- single device: `-ngl 0`;
- split: `--rpc host1:port,host2:port -ngl (offloaded+1) --override-tensor
  "^(output|output_norm|token_embd)\.(weight|bias)$=CPU"` and, with more than one Helper,
  `--tensor-split` fractions computed from each Helper's layer count (the last Helper's share includes the
  "+1" for the output layer that llama.cpp counts and that the override pins back to the Host).

**Planner rules** (`brain/Planner.kt:34-182`, mirrored in Rust `agent/src/host.rs:300+`):

| Rule | Code | Value |
|---|---|---|
| Never split a model that fits on the Host alone (unless `preferSplit`) | `Planner.kt:91` | need = weights + KV + `HOST_RESERVE` + `hostExtra` (projector) |
| Host working-buffer reserve | `HOST_RESERVE` | 300,000,000 B |
| Helper working-buffer reserve | `HELPER_RESERVE` | 150,000,000 B |
| Helper skipped if RTT too high | `MAX_RTT_MS` | 60 ms |
| Helper skipped if not charging and battery low | `MIN_BATTERY` | 20 % |
| Helper skipped if it cannot hold the biggest layer + its KV | `Planner.kt:81` | — |
| "Doable" vs "Tight" verdict | `SPARE` | ≥ 15 % spare = Doable |
| KV estimate | `kvBytes()` | f16 KV bytes/token × ctx × 17/32 (q8_0 = 34 B per 32 values) + fixed F32 recurrent state for hybrid models |
| Device memory offered | `Specs.reserveBytes`, `usableBytes` (`engine/Specs.kt:15-19`) | reserve = 15 % of RAM clamped to [0.8, 2.5] GB; free = max(`/proc/meminfo` MemAvailable, `ActivityManager.availMem`), capped by the owner's lend limit (`Offer`) |

**Split algorithm** (two passes): (1) greedy fill in order Host → Helpers sorted by usable memory
descending, to find the fewest devices that can hold all layers; (2) `balance()` re-distributes contiguous
layer counts so every chosen device fills the **same fraction** of its room (room = usable − fixed cost);
each boundary is placed where the running byte total is closest to the running target, never past a
device's room; if rounding would overflow the last device, it falls back to the greedy counts
(`Planner.kt:146-176`). Commit `e539842` measured the effect on the 30B (phone Host + laptop + phone, ctx
4096): split 18/28/2 → 14/21/13, decode **4.80 → 7.26 tok/s**, prompt 8.6 → 12.3 tok/s, load 343 s
(VERIFIED, single comparison, commit body only, not in `measurements.md`).

Placement is by memory only; device speed is not an input (VERIFIED, `Planner.kt`). A heat-aware derate was
committed and reverted the same day (`a64516d` → `8869f2c`, FAILED/REVERTED; no reason given in the revert
body). Speed scores on `origin/antigravity/speed-planner` are hard-coded by chip name and not merged
(`docs/branch-audit-2026-10-11.md:314`).

**Preflight** (`brain/Preflight.kt`): hard stop if the model file is missing or its size differs from the
plan (a size check, not a content hash; the roadmap notes hashing 18 GB is too slow). Otherwise re-checks
every Helper (left, RTT > 60 ms, battery, memory) and the Host, and also flags a split that now fits on the
Host alone; any problem triggers a re-plan with current devices, or a stop with the joined reasons.
PARTIAL: unit-tested (`PreflightTest`), not phone-checked (roadmap stage 3).

**Acceptance / rejection of devices** — summary of all gates:

| Gate | Where | Threshold |
|---|---|---|
| Pairing | `MeshHost` `hello` | one-time QR token, then a 32-byte per-Helper secret (`Secrets.random(32)`), constant-time compare |
| Link | `MeshHost.kt:420-427` | RTT = **minimum** of last 10 pings (pings every 2 s), must be ≤ 60 ms |
| Battery | `Planner.kt:80` | skip if `!charging && battery < 20` |
| Memory | `Planner.kt:81`, `Preflight.kt:217` | usable − 150 MB ≥ biggest layer (+KV) / ≥ slice bytes |
| Heat | `Specs.heat` (`PowerManager.getThermalHeadroom(10)`) | reported, **not** used to reject (comment `Planner.kt:37`) |
| Capsule | `CapsuleCheck.check` (`Capsule.kt:102-131`) | Helper rejects unsigned/forged, wrong version, wrong device, expired (10 min), invalid range, non-CPU backend; clamps threads to cores − 2 |
| Owner limit | `Offer` + StartupChecks readiness gate | owner confirms a memory ceiling before choosing a role |

### 1.4 llama.cpp RPC: what crosses the link

Facts from the pinned source (`ggml/src/ggml-rpc/ggml-rpc.cpp` at 66fba63, VERIFIED by reading):

- Commands (`:62-81`): ALLOC_BUFFER … SET_TENSOR, **SET_TENSOR_HASH**, GET_TENSOR, COPY_TENSOR,
  **GRAPH_COMPUTE**, GET_DEVICE_MEMORY, INIT_TENSOR, GET_ALLOC_SIZE, HELLO (=14), DEVICE_COUNT,
  **GRAPH_RECOMPUTE**, MEMSET_TENSOR.
- `HASH_THRESHOLD = 10 * 1024 * 1024` (`:87`): a **weights** buffer write larger than 10 MiB first sends
  only an FNV-1a 64-bit hash (`fnv_hash`, `:248-256`; `:717`, `:728-730`). If `cache_dir/<16-hex hash>`
  exists on the server (`get_cached_file`, `:1475-1495`), the server loads it locally; otherwise the client
  falls back to a full SET_TENSOR. With `-c`, the server saves received large tensors under that name
  (`:1462-1470`).
- `GRAPH_COMPUTE` sends a serialized graph: `4 + 4 + n_nodes×8 + 4 + n_tensors×sizeof(rpc_tensor)` bytes
  (`serialize_graph`, `:1006-1030`). `rpc_tensor` is packed: 8+4+8+16+16+4+64+4+80+8+8+8+64+4 = **296 bytes**
  (GGML_MAX_DIMS 4, MAX_OP_PARAMS 64, MAX_SRC 10, MAX_NAME 64; `ggml.h:222-229`). If the graph `uid`
  matches the last one sent, only a 4-byte `GRAPH_RECOMPUTE` request is sent (`:1038-1042`). The Poco
  Helper logged a graph of 248 nodes / 319 tensors (`remote_validation.md:67`) ⇒ one full graph message =
  8 + 1,984 + 4 + 94,424 = **96,420 B** (ESTIMATE from the formula), sent once per new graph shape, then
  replaced by recompute.
- **Cross-server copies go through the Host.** `ggml_backend_rpc_buffer_cpy_tensor` returns `false` when
  source and destination are on different RPC servers (`:752-770`), so the scheduler copies Helper A's output
  to the Host (GET_TENSOR) and then to Helper B (SET_TENSOR). The data path is a **star through the Host**,
  not a phone-to-phone chain. (Contradicts the diagram in `docs/UNDERSTANDING.md` §2.5, which draws
  "PHONE A ──▶ PHONE B 4 KB".)
- RPC has no authentication or encryption; upstream calls it proof-of-concept and unsafe on open networks
  (`docs/privacy-and-use-cases.md:296`).

**Per-token hidden-state size.** At each Host↔Helper boundary one activation vector of width `n_embd`
crosses per token in each direction. In llama.cpp's CPU backend the residual stream is F32, so the
transfer is **n_embd × 4 bytes** (ESTIMATE from source reading; not measured byte-by-byte). The app and the
docs use n_embd × **2** bytes (`brain/Gguf.kt:159` `hiddenBytes = embd * 2`, `ui/UseTab.kt:365`,
`docs/UNDERSTANDING.md` §2.2, `docs/research/CLAIMS-LEDGER.md`), which is likely an under-count by 2×.
Widths read from the GGUF headers on disk (VERIFIED, parsed for this report):

| Model (file on disk) | Arch | Blocks | n_embd | Heads / KV heads × dim | Per token per transfer, F32 (ESTIMATE) | App's figure (×2 B) |
|---|---|---|---|---|---|---|
| Qwen3-0.6B Q8_0 (639,446,688 B) | qwen3 | 28 | 1024 | 16 / 8×128 | 4 KiB | 2 KiB |
| Qwen3-4B-Instruct-2507 Q4_K_M (2,497,281,120 B) / Q4_0 (2,375,773,280 B) | qwen3 | 36 | 2560 | 32 / 8×128 | 10 KiB | 5 KiB |
| Qwen3-8B Q4_K_M (5,027,783,488 B) | qwen3 | 36 | 4096 | 32 / 8×128 | 16 KiB | 8 KiB |
| Qwen3.5-9B Q4_K_M (5,780,091,008 B) | qwen35 (hybrid: 24 Gated-DeltaNet recurrent + 8 full-attention blocks + 1 MTP block) | 33 (32 used: layers 0–31; `nextn_predict_layers=1`) | 4096 | 16 / 4×256 | 16 KiB | 8 KiB |
| Qwen2.5-VL-3B Q4_K_M (1,929,901,056 B) | qwen2vl | 36 | 2048 | 16 / 2 | 8 KiB | 4 KiB |
| Qwen3-Coder-30B-A3B Q4_K_M (18,556,689,568 B) | qwen3moe (128 experts, 8 used) | 48 | 2048 | 32 / 4×128 | 8 KiB | 4 KiB |

Decode traffic per token for a split with *h* Helpers ≈ 2·h transfers of n_embd×4 bytes + one 4-byte
recompute command per Helper + framing (ESTIMATE). Examples:

- 30B on laptop + 2 phones at 6.8 tok/s: 4 × 8 KiB = 32 KiB/token ⇒ ~218 KiB/s (ESTIMATE).
- 9B on 2 phones at 7.6 tok/s: 2 × 16 KiB = 32 KiB/token ⇒ ~243 KiB/s (ESTIMATE).
- Prefill moves the same per token in batches of 512 (`-b 512`): a 1,628-token prompt on the 9B split moves
  ~1,628 × 16 KiB × 2 ≈ 51 MiB (ESTIMATE). Over USB (measured 21–30 MB/s on these cables,
  `measurements.md:16,28`; `01-findings.md` §8) that is ~2 s, small against the measured 87 s read; the
  halved prefill rate on the split is therefore **not explained by bandwidth** (cause UNKNOWN; the
  instrumentation does not attribute time to compute vs RPC, `remote_validation.md:128`).
- Per-token decode time at 7.6 tok/s is 132 ms; each boundary adds at least one RTT. At USB's 2–3 ms that is
  negligible; at 50 ms (WAN) with 2 Helpers it would cost ≥ 100 ms/token by itself (ESTIMATE).

Measured link traffic (VERIFIED): ~550 MB crossed USB while the 9B 8:24 split loaded (peak ~30 MB/s
Host→laptop), little per answer (`measurements.md:28`, `data-flow.md:208`). Why 550 MB crossed even with a
layer store is not explained in the sources (possible: small tensors below 10 MiB, KV/compute buffers, or
unstored tensors) — UNKNOWN.

**KV cache per token at q8_0** (ESTIMATE from header values × 17/32): 30B: 98,304 × 17/32 = 52,224 B/token
⇒ 214 MB at ctx 4096, 856 MB at 16,384; Qwen3-8B: 147,456 × 17/32 = 78,336 B/token ⇒ 321 MB at 4096;
Qwen3.5-9B: 9 attention blocks × 4 KV heads × 256 × 2 × 2 B = 36,864 × 17/32 = 19,584 B/token ⇒ 80 MB at
4096 (+ fixed recurrent state for 24 layers). The per-layer KV share lives on whichever device holds the
layer.

### 1.5 Transports

| Path | How | Status |
|---|---|---|
| Same LAN TCP | control :7070 JSON lines (`mesh/Wire.kt`, 64 KiB max line); RPC to Helper port 50052 (fallbacks 50062/50070/50080/50100) | VERIFIED (Wi-Fi, 2.2–2.5 tok/s 9B, `remote_validation.md:106-124`) |
| USB tethering, laptop Host | each phone's `rndis0` to the laptop; 2–5 ms | VERIFIED (`measurements.md:14,18-26`) |
| USB-only, phone Host | each phone tethers to the laptop; `scripts/phone-usb-relay.py` forwards Helper→Host:7070 and Host→Helper RPC ports; Helper Wi-Fi off | VERIFIED (`measurements.md:28`: 218 samples all on rndis0, 0 on Wi-Fi). The relay can read all bytes (`data-flow.md:233-234`) |
| WebRTC DataChannel | `RtcPeer` (Unified Plan, continual gathering, optional relay-only); one ordered/reliable `rpc:n` channel per accepted TCP socket; `TcpDataChannelBridge` (≤ 3 concurrent sockets, 2 I/O workers each); `DataChannelStream` (16 KiB chunks, 1 MiB ACK window, 4 MiB inbound cap, sequence checks); Host's RPC proxy bound to 127.0.0.1 | PARTIAL: local ICE only; 8 MiB SHA-256 probe both ways passed at 0.47 MiB/s (earlier 1.44 MiB/s) |
| ICE / TURN | `remote/server.mjs` issues time-limited coturn credentials; `forceRelay` → `IceTransportsType.RELAY` | UNTESTED (OCI TCP 443 refused; VNIC not inspectable) |
| Tailscale | `Net.kt` classifies `tun*` as tailscale and prefers it | code only, no recorded run |

The WebRTC bridge needed three attempts (`remote_validation.md:154-160`): a shared channel across TCP sockets
broke RPC (FAILED), an accept-task `finally` closed sockets early (FAILED), per-connection lifecycle fixed it
(VERIFIED).

### 1.6 Layer caching: hash path and LayerStore

`brain/LayerStore.kt` reads each GGUF on the Helper, computes FNV-1a 64 of every tensor > 10 MiB and writes it
as `files/cache/rpc/<hash>` — exactly where `ggml-rpc-server -c` looks (`LLAMA_CACHE=files/cache`,
`engine/Engine.kt:96`). An index (`cache/stored.json`) records which layer ranges are complete, reported to
the Host in `specs` messages (Models page shows which planned layers each Helper already has, commit
`7da5fbc`). Design choice: store **every** layer of the model on every phone so any future split is warm
(144 tensors, 17.6 GB per phone for the 30B, `measurements.md:20`). Disk cost = model size per phone.

Evidence: hash of `blk.40.ffn_down_exps` = `94a16f7ad603732b`, identical to the engine's file
(`LayerStoreTest`); readiness **437 s (unstored) → 70 s (stored)** for a never-used split
(`measurements.md:19-20`); first-ever load from HDD 517 s (`:18`). On the Poco, 20 tensors (609,878,016 B)
were present and `set_tensor_hash` calls were logged, but no per-hash hit/miss counter exists
(`remote_validation.md:132-136`) — byte-exact avoided-transfer proof is MISSING.

### 1.7 Ned Capsules (two different things share the name)

1. **Run capsule** (`brain/Capsule.kt`): Host → Helper work order: id, run id, Helper id, model name/file/
   bytes, layer range (inclusive), ctx, proposed threads (cores − 2), backend "CPU", expiry (10 min),
   version 1, **HMAC-SHA256 with the pair's 32-byte secret** over a `|`-joined body. Helper verifies and
   returns `Applied{from,to,threads,backend,local_file:"matches|differs|none",notes}`; Host shows proposed vs
   applied. Carries no weights or prompts. VERIFIED once on phones (`measurements.md:29`: layers 8–31, 6
   threads, CPU, model file matches). Note: the Helper's actual `ggml-rpc-server` start uses the plain run
   fields; the capsule is checked and reported, it does not by itself change what the server loads.
2. **Compiler capsule** (`brain/CompilerCapsule.kt`): JSON written by `scripts/export-capsules.py` from the
   Mesh Compiler DB: device model, model file/bytes, task (`chat` = 100 in/150 out, `agent` = 2,000 in/300
   out), scope, `enact` (engine, threads, ctk, ctv, ub, b, fa, ngl), evidence (run ids, quality, task
   seconds). The app refuses non-CPU engines, a different model size, a different engine build, or `ngl > 0`.
   Of the three exported capsules (`docs/mesh-compiler/capsules/`), the `agent/any` one (OpenCL, ngl 36) is
   **refused** by the CPU-only app; the two CPU ones are the **baseline** (`is_baseline: true`), so applying
   them changes `-b` from 512 to 2048 and nothing else. They are not yet on the phones (roadmap stage 7).

### 1.8 Data flow and security boundaries

```
 user ──question──▶ Host UI ──HTTP POST 127.0.0.1:8080 (bearer key)──▶ llama-server (Host)
                      │                                                 │ token_embd/output/norm pinned on Host
   Case/Code files ───┘ read into memory only (no disk copy)           │
                                                                         ├── activations (F32, per token) ──▶ Helper(s)
                                                                         │      (plaintext TCP; or DTLS over WebRTC)
                                                                         ◀── activations back ───────────────┘
 answer ◀── stream ◀────────────────────────────────────────────────────┘
 Stats (numbers only, SharedPreferences "mesh-stats", last 100); Spider Sense (names/counts/timings, last 80)
 Laptop: sees control JSON (specs, ping, activity events); in USB-relay mode also forwards (could read) RPC bytes
```

| Boundary | Mechanism | Status / gap |
|---|---|---|
| Model API | random per-install `LLAMA_API_KEY`; llama-server binds **0.0.0.0:8080**; key shared only with paired laptop CLI | VERIFIED code; HTTP is plaintext; health/metadata endpoints may be open (`privacy-and-use-cases.md:266`) |
| Pairing | QR invite {mesh id, host IPs, port 7070, one-time token}; then 32-byte secret per Helper stored in SharedPreferences | VERIFIED; no device attestation |
| Helper RPC port | `ggml-rpc-server -H <local address of the control socket>` (`MeshClient.kt:158`, `Engine.kt:50`) — bound to the LAN/USB interface, **unauthenticated** | Gap: any host on that network can talk RPC to the Helper. Roadmap §8 goal "keep native RPC bound to local interfaces" is met only on the WebRTC path (127.0.0.1 proxy) |
| Activations | visible to every Helper; inversion attacks exist (ActInv, arXiv 2605.23158, cited in `CLAIMS-LEDGER.md`) | Mitigation is trust: Case Room/Code require ticking each Helper; consent re-asked when the set changes |
| Encryption | none on local TCP; DTLS on WebRTC | `data-flow.md:233` |
| Backup | `android:allowBackup="false"` (`AndroidManifest.xml:25`) | VERIFIED |
| Cleartext | `usesCleartextTraffic="true"` (`AndroidManifest.xml:24`) | needed for local HTTP |
| Prompt in logs | engine keeps last 60 log lines; whether they contain prompt text **not verified** | `data-flow.md:237-238` |
| Memory erasure | none (Compose state cleared, not wiped) | `data-flow.md:239` |
| Signalling | never sees RPC bytes; tokens compared in constant time | code VERIFIED, deployment UNTESTED |

---

## 2. Models and intelligence

### 2.1 Every model referenced or on disk

Sizes are file sizes on `/mnt/storage/meshai/models` (VERIFIED `ls -l`); layer counts from GGUF headers
(VERIFIED). "Used in" cites the measurement row.

| Model | Quant | File bytes | Blocks | Used in | Licence (upstream) |
|---|---|---|---|---|---|
| Qwen3-Coder-30B-A3B-Instruct | Q4_K_M | 18,556,689,568 | 48 (MoE 128×, 8 active, ~3.3B active params per `UNDERSTANDING.md` §2.6) | 30B mesh runs, Aider, 15/15 bench | Apache-2.0 (Qwen) |
| Qwen3.5-9B (+ `mmproj-Qwen3.5-9B-f16`, 918,165,952 B) | Q4_K_M | 5,780,091,008 (SHA-256 `ef6f244d…9c93`) | 33 (32 + MTP) | 11 Oct phone runs, WebRTC/TCP validation | Apache-2.0 (Qwen) — not re-verified |
| Qwen3-8B | Q4_K_M | 5,027,783,488 | 36 | phone alone 11.8 tok/s, sustained heat, Aider laptop | Apache-2.0 |
| Qwen3-4B-Instruct-2507 | Q4_K_M / Q4_0 | 2,497,281,120 / 2,375,773,280 | 36 | Mesh Compiler (all 122 runs); laptop Case Room prompt checks (Q4_0) | Apache-2.0 |
| Qwen2.5-VL-3B-Instruct (+ mmproj f16 1,338,428,128 B) | Q4_K_M | 1,929,901,056 | 36 | vision on emulator and iQOO, routed vision 19.1 tok/s | Qwen research licence (not re-verified) |
| Qwen3-1.7B | — | MISSING (not on disk) | — | 15-problem bench (laptop) | Apache-2.0 |
| Qwen3-0.6B | Q8_0 | 639,446,688 | 28 | phone 78.1 tok/s, emulator split, bench | Apache-2.0 |
| Qwen3-ASR-0.6B (+ mmproj) | Q8_0 | 804,749,248 (+214,392,480) | — | none recorded | — |
| Qwen3-TTS-12Hz-1.7B-Base (+ mmproj Q8_0 / bf16) | Q4_K_M | 1,035,965,280 | — | none recorded | — |
| Stable Diffusion v1.5 | Q8_0 | 1,763,578,176 | — | none (image generation out of scope, `CLAUDE.md`) | CreativeML OpenRAIL-M |
| Qwen3-Coder-REAP-25B-A3B | — | not on disk | — | proposed pruning target only (`01-findings.md` §4b) | PLANNED |

The app's built-in `Catalog` (`brain/Catalog.kt`) lists only four (30B, 8B, VL-3B, 0.6B) with averaged
per-layer sizes; Qwen3.5-9B and Qwen3-4B are handled from their own headers when installed.

### 2.2 Task detection and routing

`TaskDetector.detect()` (`brain/TaskDetector.kt`) is regex-only, no model call: photo → VISION; strong code
shapes (``` blocks, `def/fun/class…` lines, stack traces, exception names) or two code words → CODE;
≥ 2,500 chars or document words → DOCUMENT; maths words/shapes → REASONING; else CHAT. Tests:
`TaskDetectorTest`.

`Router.choose()` (`brain/Router.kt`): filters to models with a projector for VISION; sorts suited models
first (CODE ⇔ "coder" in name; others ⇔ not coder), then REASONING prefers names matching
`r1|qwq|math|thinking|reason`, DOCUMENT/CASE prefer longest `contextMax`, else largest file; keeps a running
model if it suits the task; otherwise takes the first model whose plan is not NOT_POSSIBLE. Never downloads,
never forces a split. PARTIAL: phone saw "reasoning → Qwen3.5 9B" (roadmap stage 4); a switch between two
installed models on the phones has not been shown ("a coder model that fits, to show a switch" missing).
Model quality is **not** an input (no task-quality measurements feed it).

### 2.3 Mesh Compiler (separate repository)

- **Search**: Optuna TPE, multi-objective over {Q4_K_M, Q4_0} × {cpu, opencl} × GPU layers 9–36 × KV
  {q8_0, f16} × threads 4–8 × ubatch 128–1024 × flash-attn; objective = two task times (chat 100/150,
  agent 2,000/300 tokens; `time = in/prompt_tps + out/decode_tps`). 16 complete trials, resumed twice
  after cable drops (`02-implementation.md`). Cool-down to ≤ 35 °C (later 40 °C) before each trial; noise
  guard > 3 % and > spread.
- **Quality gate**: the same 15-problem bench as `scripts/bench.py`, run through llama-server on the phone.
- **Results** (Qwen3-4B, iQOO 15, single phone): GPU (OpenCL, Adreno 840) prompt reading 65.8 → 190–195
  tok/s (~3×), decode 20.0 → 12.3–15.7 tok/s (21–39 % slower); agent step −22 to −35 %, chat +12 to +48 %
  slower; quality 14/15 both (same failure `parse_duration`). Full table in §4.4.
- **Export → app**: `scripts/export-capsules.py` → JSON capsules → `CompilerCapsule.choose()`; see §1.7.
  Net effect today: no tuned setting is active on any phone (MISSING).
- Proposed but unbuilt: per-task manifest/predictor, REAP expert pruning, n-gram speculation, core pinning
  (`01-findings.md` §4, §4b; `04-task-compiler-research.md`) — PLANNED.

### 2.4 On-phone Tune, learning from use, AnswerCheck

- **Tune** (`brain/Compiler.kt`, `ui/CompilerCard.kt`): candidates = current, cores−2, cores−4, cores/2, 4
  (≤ 4 distinct, ≥ 2); each restarted (~48 s per restart on the 9B split) and timed 3× on a fixed prompt
  (48 tokens, temp 0, seed 1, `cache_prompt` false); median decode; adopt only if ≥ 5 % faster
  (`MIN_GAIN`) and no failed runs. Result 11 Oct 08:1x: 6 threads 7.6 tok/s vs 4 threads 7.1 → kept 6
  (VERIFIED). Run at ~08:05 **void**: restarts showed "Ready · 0 s" then the new server exited with 1
  because the old one held :8080 and still answered `/health` — Tune timed the old process (commit
  `15962c6`, FAILED; fixed by waiting up to 15 s for the port, `Runner.kt:177-178`).
- **Learning from use** (`0d05deb`): every answer ≥ 20 tokens is a sample (newest 8 per thread count) keyed
  by phone model | model file | bytes | ctx | split; when opt-in learning is on, under-sampled candidates
  are tried on later runs; a ≥ 5 % winner after 3 samples each becomes the profile. Precedence: explicit
  Tune > learning > saved profile > compiler capsule > default (cores − 2). PARTIAL (unit only).
- **AnswerCheck** (`brain/AnswerCheck.kt`, `54d9364`): 8 exact-match items (17×23=391, Canberra, 366, "hsem",
  `len([4,5,6,7])`=4, 97 prime, 0.9 > 0.11, a cited "Thursday [S01]"). `decide()` never adopts a faster
  setting with fewer correct answers than baseline. PARTIAL: unit tests only; "Not yet run on the phones"
  (commit body). Threads do not change answers, so this gate is preparation for future knobs.

### 2.5 Spider Sense, hardware profiling, stats

- **Spider Sense** (`brain/Sense.kt`): in-memory, last 80 events, kinds LINK/MODEL/WORK/ASK/FAIL; Helper
  `graph_compute`/`graph_recompute` events come from the Helper's `GGML_RPC_DEBUG` log via the control
  link; bursts merged (3 s, WORK 15 s). An event proves a command reached the Helper, not a compute time
  (`privacy-and-use-cases.md:264`).
- **Specs** (`engine/Specs.kt`): id (ANDROID_ID), name, chip (`SOC_MODEL`), cores, max GHz (cpufreq),
  total/free memory (/proc/meminfo vs ActivityManager, the larger — fixes under-reporting found in
  `8361268`), thermal headroom, battery %, charging; re-read every couple of seconds.
- **Stats** (`brain/Stats.kt`): per answer model, devices, layout, tok/s, TTFT, tokens, heat, prompt tok/s,
  link, KV bytes, free bytes, task, prompt tokens; last 100 in SharedPreferences.

---

## 3. Features and applications

| Feature | Implementation | Limits | Status |
|---|---|---|---|
| Chat (Use → Mesh Chat) | Compose chat over `Chat` client to 127.0.0.1:8080; every message routed by TaskDetector/Router; markdown; Stop; survives tab switches (`fd3773e`) | one conversation slot (`-np 1`); reasoning off | VERIFIED (phone) |
| OpenAI-compatible API | llama-server `/v1/chat/completions` on Host :8080 with bearer key | plaintext HTTP; one slot | VERIFIED (`mesh ask`, Aider, bench.py) |
| CLI | `mesh join`, `ask`, `review`, `tests`, `diff`, `hook`, `host`, `panel`, `route` (`agent/src/main.rs:1-16`) | Rust; laptop only | VERIFIED (`measurements.md:10,13,14,17`) |
| Agentic coding | Aider 0.86.2 via `desktop/aider/run-aider.sh` and `desktop/service.py` task runner (edit/commit/tests on, shell off) | pinned split; one demo task | VERIFIED (3 runs) |
| Code room (Developer Workspace) | `ui/CodeFiles.kt`: Android folder picker; skips .git/build/node_modules/binaries/.env; ≤ 400 files, ≤ 256 KiB each, ≤ 3,000,000 chars; camelCase-aware lexical search; answers cite file:lines; read-only | no execution; lexical only | PARTIAL (2 phone runs: single 1,628-token prompt 35.8 tok/s; split 18.6 tok/s) |
| Legal Case Room | `ui/CaseDocuments.kt`: ≤ 8 files; PDF ≤ 18 MiB & ≤ 120 pages (PDFBox-Android text extraction), text ≤ 4 MiB; ≤ 500,000 extracted chars; passages 1,300 chars; BM25-style lexical retrieval with word forms, ≤ 6 passages / 5,600 chars; whole-case fallback labelled; `CaseCitations.check` validates `[Sxx]` IDs (full/partial/unknown); `CaseTimeline` regex date extraction (≤ 80 events); `CaseConflicts` model JSON over ≤ 40 events, checked; consent per Helper set; progress stages + stats card | no OCR; citation check proves IDs, not support; not legal advice | PARTIAL (import/search verified on phone; one split Ask with `[S01]`; timeline/conflicts/progress unit-only; uncommitted edits in progress by another agent) |
| Origami Lab | `origami/app`: parametric generators (organizer 1–4 compartments; table: top + 2 legs + stretcher; bookshelf 2–6 shelves); 10-item catalogue; tab-and-slot, wedged-tenon (WEDGE_BITE 0.5 mm) and finger-jointed boxes for storage items (`dd209f3`) with a 3D check on every joint; slots and wedge holes cut through in 3D and "show this joint in 3D" zoom (`00d53d9`); Realistic↔Lines crash fixed (`42a4512`); sheet layout (shelf packing, 90° rotations, 5 mm margin, 2 mm gap; stock 600×400, 600×600, 900×600, 1220×2440 mm); SVG 1:1 with kerf 0/0.1/0.15/0.2 mm; project JSON; Filament 1.71.4 realistic 3D (GLB, procedural birch, shadows, AO, explode); "Will it hold?" beam check (5FL³/384EI, FL³/48EI; plywood 8 GPa/35 MPa, MDF 3.5/25, acrylic 3/70; ×3 safety; L/360, L/180); optional MeshAI bridge may set only compartments and clearance | no fabrication test; material values are handbook estimates; finger-box toy box render not yet reviewed on phone (`dd209f3`); packer not true nesting | PARTIAL (49 `@Test`s counted before `00d53d9`/`dd209f3`, which add Mesh3DTest and FingerBoxTest cases; 3D seen on Helper iQOO) |
| Dashboards | Rust `host.html` (laptop Host, shows phone mesh), `panel.html`, desktop window; `scripts/helper-view.py`; `StatusFile.kt` feeds the laptop panel from what apps report | observer only | PARTIAL ("laptop-panel vs app check" missing) |
| Two-model routing | `mesh route`: text → phone B, photo → phone A via `X-Mesh-Route` | laptop process | VERIFIED once (`measurements.md:17`) |

---

## 4. Performance measurements

All rows are copied from their sources; nothing here was re-run. Engine llama.cpp 66fba63 CPU unless stated.
Devices: iQOO 15 = vivo I2501, Snapdragon SM8850 ("8 Elite Gen 5"), 16 GB; POCO F5 = SD 7+ Gen 2, 8 GB
(7,354,340 kB MemTotal); laptop = "i5, capped at 8 GB" (exact CPU MISSING).

### 4.1 `docs/measurements.md` (every row)

| # (line) | When | Devices | Model / quant | Layers | Link | Ctx | Decode tok/s | Prompt tok/s | Memory | Other |
|---|---|---|---|---|---|---|---|---|---|---|
| 7 | 26 Sep 13:25 | iQOO #1 alone (adb) | Qwen3-0.6B Q8_0 | 28/28 | — | 4096 | **78.1** | 768.7 | 1.77 GB RSS | 6 thr, 128 tok, 95 % battery |
| 8 | 26 Sep 13:45 | laptop Host + iQOO helper | 0.6B Q8_0 | 15/28 on phone | venue Wi-Fi 266–483 ms (avg 373) | 2048 | — | — | 9 MB | **FAILED**: not loaded after 60 s |
| 9 | 26 Sep 14:09 | POCO F5 alone, app | 0.6B Q8_0 | 28/28 | — | 4096, KV q8_0 | 23.1 | — | — | first word 0.3 s, 12 tok |
| 10 | 26 Sep 14:11 | POCO Host + laptop `mesh join` | — | — | USB tether | — | — | — | — | pairing; 8.9 GB pooled |
| 11 | 26 Sep 14:49 | emulator Host + laptop helper | 0.6B Q8_0 | Host 0–8, laptop 9–27 | emulator NAT | 4096 | 11.9 | — | helper 475 MB RSS | correct answer; helper kill → "A helper left" |
| 12 | 26 Sep 14:58 | emulator alone | Qwen2.5-VL-3B Q4_K_M + mmproj f16 | 36/36 | — | 4096 | — | — | — | photo described; ~1–2 min/photo |
| 13 | 26 Sep 15:04 | iQOO #1 alone, app | Qwen3-8B Q4_K_M | 36/36 | HTTP over Wi-Fi | 4096, q8_0 | **11.8** | — | — | ready ~8 s; 98-token answer |
| 14 | 26 Sep 15:50 | iQOO Host (cap 3 GB) + laptop helper | VL-3B (text) | phone 0–21, laptop 22–35 | USB, 0.3–64 ms (avg 6.9) | 4096, q8_0 | 9.2 | — | helper 679 MB RSS | first load ~3 min |
| 15 | 26 Sep 16:25–16:36 | iQOO alone, 10 min | 8B Q4_K_M | 36/36 | — | 4096 | **11.4 → 5.3** | — | — | skin 34.6→47.5 °C; batt 30.5→42.0 °C; `sustained-8b-iqoo.csv` |
| 16 | 26 Sep 16:50 | laptop → iQOO file copy | 0.6B file 0.64 GB | — | USB | — | — | — | — | ~27 s, ~24 MB/s; 30B ⇒ ~13 min (ESTIMATE) |
| 17 | 26 Sep 18:45 | iQOO A (vision) + B (text), `mesh route` | 8B on B; VL-3B on A | whole | adb forward | 4096 | text 12.3; vision 19.1 | vision 12.8 (2,271-tok screenshot) | — | both correct |
| 18 | 26 Sep 20:18 | laptop (cap 8 GB) + ·8a75 + ·8373 | 30B Q4_K_M | 0–17 / 18–35 / 36–47 | USB 2–3 ms | 4096, q8_0 | **6.6–7.1** | 6.9–24.4 | phones 6.19 / 4.49 GB RSS | ready 70 s; first load 517 s (HDD) |
| 19 | 27 Sep 00:5x | same, before stored layers | 30B | 0–19 / 20–36 / 37–47 | USB | 4096 | — | — | ~3.7 / 3.3 GB | **ready 437 s** |
| 20 | 27 Sep 01:10 | same, after storing | 30B | 0–22 (9.19 GB) / 23–42 (7.53) / 43–47 (2.04) | USB | 4096 | 6.6 | — | — | **ready 70 s**; 144 tensors, 17.6 GB/phone stored |
| 21 | 27 Sep 01:25 | phones full share first | 30B | 0–8 (4.0 GB plan) / 9–27 (7.07) / 28–47 (7.69) | USB 2–3 ms | 4096 | **6.7–7.1** | — | RSS laptop 4.09, ·8373 7.33, ·8a75 6.41 GB | ready 87 s |
| 22 | 27 Sep 05:28 | same, after reboot | 30B | 0–14 / 15–28 / 29–47 | USB 2–3 ms (Wi-Fi at 05:25 13–181 ms, worst 10.5 s) | 4096 | **7.2** (120 tok) · **7.3** (307 tok) | 19.2 · 31.9 (359-tok) | — | ready 69 s; first word 1.1 s / 11.6 s; Wi-Fi: 94 s first word, cut off (**FAILED**) |
| 23 | 27 Sep 06:10 | laptop alone, Aider | 8B Q4_K_M | 36/36 | — | 16384 | 2.5 | 9.6 (2,757 tok) | — | first word 287 s, total 459 s, 5 tests pass |
| 24 | 27 Sep 06:41 | laptop + both iQOO, Aider | 30B | pinned 0–19 / 20–33 / 34–47 | USB | 16384 | 5.4 | 21.1 (1,003 tok) | — | 603 s, commit 4a5e308, 5 pass |
| 25 | 27 Sep 08:18 | same, desktop Send | 30B | pinned | USB 2–4 ms | 16384 | 4.4–5.1 | 19.1 (2,143 tok) | phones 4.8 / 4.6 GB (app) | **7 min 34 s**, commit fa1a81c, 5 pass |
| 26 | 27 Sep 09:04 | same, `bench.py` | 30B | pinned | USB 2–5 ms | 16384 | 6.8 median (5.5–7.9) | — | — | **15/15**, 20.7 s median, 300.9 s total |
| 27 | 11 Oct 06:38 | iQOO `…01GG` alone, app | Qwen3.5-9B Q4_K_M | all | — | 4096, q8_0, `-b 512` | 7.5 (160 tok) | **35.8** (1,628 tok) | — | Code room; start 34 s, first word 45 s, total 66 s after start |
| 28 | 11 Oct 07:08 | iQOO `…00XP` Host (cap 5 GB) + `…01GG` Helper, USB-only via relay | 9B | 0–7 / 8–31 | USB both, laptop relay | 4096 | 7.5 (15 tok) | 14.8 (172 tok) | — | Case Room `[S01]`; first word 11 s; ~550 MB at load |
| 29 | 11 Oct 08:1x | same, build `15962c6` | 9B | 0–7 / 8–31 | USB relay | 4096 | **7.6** (6 thr) · 7.1 (4 thr) | 9.3 · 8.9 (short, cache off) | — | Tune, 3 runs/setting, ~48 s per restart; capsule applied; **~08:05 Tune void** |
| 30 | 11 Oct 08:2x | same | 9B | 0–7 / 8–31 | USB relay | 4096 | 6.5 (72 tok) | **18.6** (1,628 tok) | — | Code room first question: 87 s read, 98 s total; follow-up reuse **not captured** (MISSING) |

### 4.2 `docs/remote_validation.md` (11 Oct; iQOO `…01GG` Host + POCO F5 Helper; Qwen3.5-9B Q4_K_M; Wi-Fi LAN)

| Run | Split | Transport | Prompt / gen tokens | TTFT | Prompt eval | Decode | Wall | Notes |
|---|---|---|---|---|---|---|---|---|
| WebRTC first completion | Host 0–26 / Helper 27–31 | WebRTC local ICE | 20 / 5 | not captured (non-streaming) | 7.06 s, 2.83 tok/s | 2.89 tok/s | 8.48 s | load ~51.4 s; output "remote RPC completion works" |
| WebRTC benchmark | 0–26 / 27–31 | WebRTC local | 99 / 128 (ignore_eos) | 12.85 s | 7.74 tok/s | **2.22** | 69.98 s | one run; temp 0, seed 1729 |
| Screen-off | 0–26 / 27–31 | WebRTC local | 30 / 16 | 13.23 s | 4.18 tok/s | 2.48 | — | `mesh:engine` wake lock held |
| Historical direct TCP | 0–26 / 27–31 | TCP | 20 / 5 | — | 1.78 tok/s | 2.70 | 12.71 s | load ~37.88 s |
| Direct TCP ×5 | 0–23 / 24–31 (`-ngl 9`) | TCP, 11 ms RTT | 112 / 128 | 38.69–47.78 s | run 1 2.90 tok/s; runs 2–5 cached (0.09–0.14*) | 2.28, 1.75, 2.48, 2.64, 2.52 (mean **2.33**, median 2.48) | mean 98.99 s | caps iQOO 6 GB / Poco 4 GB; Host RSS ~7.0 GiB; Helper RSS ~0.96 GiB; TTFT vs server timing diverged up to ~18 s (UNEXPLAINED) |
| 8 MiB SHA-256 probe | — | WebRTC | — | — | — | — | — | passed both ways; 0.47 MiB/s (earlier 1.44) |

Not comparable to the iQOO+iQOO USB runs (different Helper SoC, Wi-Fi link). The POCO Helper is 3× slower
than the iQOO pair for the same model (2.2–2.5 vs 7.5 tok/s); attribution (Helper compute vs link) MISSING.

### 4.3 `results/REPORT.md` (15 coding problems, answers executed against tests, temp 0)

| Setup | Passed | Median tok/s | Median s/task | Total s | When |
|---|---|---|---|---|---|
| laptop + 2 phones, 30B | 15/15 | 6.8 | 20.7 | 300.9 | 27 Sep 09:04 |
| laptop alone, 0.6B | 2/15 | 20.9 | 5.3 | 89.5 | 27 Sep 03:45 |
| laptop alone, 1.7B | 7/15 | 8.6 | 13.8 | 216.0 | 27 Sep 03:43 |
| laptop + laptop helper, 1.7B split | 7/15 (same 8 failures) | 7.4 | 14.3 | 251.0 | 27 Sep 03:36 |
| (earlier, 8-problem set, commit `ccb7003`/`cf85837` era) 0.6B / 1.7B | 2/8, 5/8 | 22.1 / 9.0 | — | — | 27 Sep, superseded (`results/superseded/`) |

Wilson 95 % CI for 7/15 is [24.8 %, 69.9 %] (`CLAIMS-LEDGER.md`). 15/15 is a ceiling result.

### 4.4 Mesh Compiler (`docs/mesh-compiler/03-results.md`; DB rows; Qwen3-4B-Instruct-2507; one iQOO 15; 10 Oct 01:04–03:55)

Baseline (MeshAI settings: CPU, 6 thr, q8_0, b512, FA on): prompt 65.8 ± 2.8, decode 20.0 ± 0.1; chat 9.0 s,
agent 45.4 s (#1–2).

| Runs | Phase | Quant | Settings | Prompt tok/s | Decode tok/s | Chat s | Agent s |
|---|---|---|---|---|---|---|---|
| #3–4 | screen | Q4_K_M | CPU 6 thr q8_0 b512 FA | 61.3 | 19.9 | 9.2 | 47.7 |
| #5–6 | screen | Q4_K_M | GPU all, q8_0 | 190.3 | 12.3 | 12.7 (+41 %) | 34.8 (−23 %) |
| #7–8 | screen | Q4_K_M | GPU all, f16 | 195.2 | 15.7 | 10.1 (+12 %) | 29.4 (−35 %) |
| #9–10 | search | Q4_K_M | CPU 6 q8_0 | 68.3 | 20.1 | 8.9 | 44.2 |
| #11–12 | search | Q4_K_M | GPU 36, 6 thr, f16 | 186.3 | 14.3 | 11.0 | 31.7 (−30 %) |
| #13–14 | search | Q4_0 | GPU 36, 4 thr, q8_0, b1024 | 182.0 | 12.3 | 12.8 | 35.5 |
| #15–16 | search | Q4_0 | GPU 36, 8 thr, f16, FA off | 190.8 | 13.6 | 11.5 | 32.5 |
| #17–18 | search | Q4_K_M | GPU 18, 8 thr, q8_0, b128 | 118.9 | 12.1 | 13.2 | 41.5 |
| #19–20 | search | Q4_K_M | GPU 18, 7 thr | 93.5 | 11.2 | 14.4 | 48.1 |
| #21–22 | search | Q4_0 | GPU 36, 5 thr | 185.9 | 11.8 | 13.3 | 36.3 |
| #23 | search | Q4_K_M | GPU 27, 5 thr, q8_0, b1024, FA off | **FAILED** (context creation) | | | |
| #24–25 | search | Q4_K_M | GPU 36, 8 thr, f16, b256 | 186.2 | 12.3 | 12.8 | 35.2 |
| #26–27 | search | Q4_0 | GPU 36, 7 thr, f16, b128 | 184.9 | 13.7 | 11.5 | 32.8 |
| #28–29 | search | Q4_K_M | GPU 9, 4 thr, f16, b256 | 68.3 | 13.2 | 12.8 | 52.0 (+15 %) |
| #30–31 | search | Q4_K_M | CPU 6 q8_0 | 58.8 | 20.4 | 9.1 | 48.8 |
| #32–33 | search | Q4_K_M | GPU 9, 5 thr, f16 | 66.7 | 13.6 | 12.5 | 52.0 |
| #34–35 | search | Q4_0 | CPU 7 thr, b1024 | 70.9 | 19.8 | 9.0 | 43.3 |
| #36–37 | search | Q4_0 | CPU 6 thr, b256 | 28.0 | 17.9 | 12.0 | 88.2 (+94 %) |
| #38–39 | search | Q4_0 | CPU 7 thr, b1024 | 30.6 | 16.6 | 12.3 | 83.4 |
| #40–41 | search | Q4_0 | CPU 5 thr, f16, b1024 | 65.5 | 18.2 | 9.8 | 47.0 |
| #42–43 | search | Q4_K_M | GPU 27, 6 thr, f16, FA off | 88.3 | 10.3 | 15.7 | 51.8 |
| #44 | search | Q4_0 | CPU 8 thr, b128, FA off | **FAILED** (context creation) | | | |
| #45–46 | confirm | Q4_K_M | CPU baseline | 44.7 ± 8.5 | 16.1 | 11.5 | 63.4 (+40 %, phone in use) |
| #47–48 | calibration (2nd phone ·00XP) | Q4_K_M | CPU baseline | 72.4 | 21.2 | 8.5 | 41.8 |
| #50–51 | confirm | Q4_K_M | GPU 36 f16 | 159.1 | 13.1 | 12.1 | 35.6 (−22 %) |
| #49 | 2k prompt | Q4_K_M | CPU baseline | 24.9 ± 3.5 (82.3 s to read 2,048) | | | |
| #52 | 2k prompt | Q4_K_M | GPU 36 f16 | 108.0 (19.0 s, −77 %) | | | |
| #68 / #80 | quality | Q4_K_M | CPU baseline / GPU 36 f16 | 14/15 each (fail `parse_duration`); 18.9 / 10.9 tok/s; 124.5 / 195.8 s | | | |
| #53–79 | sustained | GPU winner (·01GG) | 11.3 → 13.6 tok/s over 372 s; batt 40.0 → 39.5 °C | | | | |
| #88–122 | sustained | CPU baseline (·00XP) | 18.8 → 19.2 tok/s over 348 s; 1 sample excluded (#97, 0.22 tok/s, phone in use) | | | | |

Negative results: GPU **slows** decode on every configuration (best 15.7 vs CPU 20.0–21.2) and slows chat
(+12 % to +60 %); CPU Q4_0 with small batch was much worse (#36–39); run-to-run variance from the owner
using the phone reached 40 % (#45–46). The two-phone calibration differed by ≤ 6 %.

**Prompt reuse** (`--cache-reuse 256`, ~1,900-token context = 1,778 tokens processed uncached; 4B):

| Setting | No cache: prompt / reply | Repeat same prompt | Same context, new task (12–21 tokens processed) | File edited mid-way (1,435 tokens) |
|---|---|---|---|---|
| GPU winner | 13.1 s / 15.3 s | 0.1 s / 2.14–2.17 s | 0.6–0.7 s / 2.41–3.71 s | 11.2 s / 13.17 s (−15 %) |
| CPU baseline | 61.5 s / 63.34 s | 0.2 s / 1.87–1.98 s | 0.7–1.1 s / 2.38–2.8 s | 53.6 s / 55.37 s (−13 %) |
| GPU winner (clean) | 11.3 s / 13.26 s | 0.1 s / 2.11 s | 0.5–0.6 s / 2.18–3.59 s | 9.6 s / 11.36 s |
| CPU baseline (clean) | 48.7 s / 50.42 s (first "repeat" 54.8 s / 56.32 s, cache cold) | 0.1 s / 1.79 s | 0.6–1.1 s / 2.2–2.78 s | 48.4 s / 50.05 s (−1 %) |
| CPU f16, FA off | 54.0 s / 55.64 s | 0.1 s / 1.72 s | 0.5–0.9 s / 2.1–2.53 s | 48.2 s / 49.94 s |

Prompt reuse on the two-phone **split** was seen by the owner but not recorded (MISSING;
`measurements.md:30`). The app enables reuse implicitly via `-np 1` and llama-server's default
`cache_prompt`; `--cache-reuse` is not passed by `EngineArgs` (VERIFIED by reading) — the KV-shift reuse
after a mid-prompt edit measured above is therefore not active in the app.

### 4.5 Measurements found only in commit bodies or other docs

| Source | Measurement | Status |
|---|---|---|
| `e539842` (10 Oct) | 30B, phone Host `…01GG` + laptop + phone `…00XP`, ctx 4096: split 18/28/2 → 14/21/13; decode 4.80 → 7.26; prompt 8.6 → 12.3 tok/s; load 343 s | VERIFIED (commit only) |
| `7d38cb5` (10 Oct) | iQOO↔iQOO Wi-Fi RTT: 103 ms median at idle vs 17.6 ms with steady traffic; after fix 16 ms, 30B on 3 devices 7.26 tok/s | VERIFIED (commit only) |
| `ca42060` (10 Oct) | 30B answer 5.7 tok/s on phone chat (screenshot `docs/screenshots/phone-chat-light-10oct.png`) | VERIFIED (commit only) |
| `01-findings.md` §7–8 | adb meminfo read ~27 ms; dumpsys battery ~36 ms; analyzer 17 MB, 11 ms over 10,000 runs; screenshot 0.56 s; HF 77 KB/s on one connection; POCO 5G 2.38 GB in 70 s; POCO USB tether 21.4 MB/s; `adb pull` 42 MB/s | VERIFIED (that repo) |
| `docs/UNDERSTANDING.md` §2.6 | "dense 8B at 11.8 tok/s implies ~55 GB/s" | ESTIMATE |
| `sustained-8b-iqoo.csv` | 15 samples, 11.44 → 9.09 at 546 s, then **5.29** at 607 s (the final drop rests on one sample) | VERIFIED; "51 % of peak averaged over an hour" is an ESTIMATE (`UNDERSTANDING.md` §4.4) |

### 4.6 Cold vs warm load, TTFT, startup

| Event | Value | Source |
|---|---|---|
| 30B first load from laptop HDD | 517 s | `measurements.md:18` |
| 30B split, layers not stored | 437 s | `:19` |
| 30B split, stored layers | 69–87 s | `:18,20,21,22` |
| 30B load, phone Host + laptop + phone | 343 s | `e539842` |
| 30B pinned split auto-start | 72 s | `:25` |
| 9B on one phone, model start | 34 s | `:27` |
| 9B split restart during Tune | ~48 s | `:29` |
| 9B WebRTC split load | ~51.4 s (TCP ~37.88 s) | `remote_validation.md:70,104` |
| VL-3B phone+laptop first load | ~3 min | `:14` |
| 8B phone ready | ~8 s | `:13` |
| TTFT 30B short / 359-token | 1.1 s / 11.6 s (USB); 94 s Wi-Fi then cut | `:22` |
| TTFT 9B 1,628-token prompt | 45 s alone / 87 s split | `:27,30` |
| TTFT 9B Case Room 172 tokens split | 11 s | `:28` |

### 4.7 Memory

30B 01:25 run RSS: laptop llama-server 4.09 GB, ·8373 7.33 GB, ·8a75 6.41 GB (phones = 13.74 GB, 74 % of
18.56 GB). 9B TCP validation: Host RSS ~7.0 GiB, POCO RPC ~0.96 GiB. 0.6B alone 1.77 GB RSS. Laptop
helper 475 MB (0.6B), 679 MB (VL-3B). Peak memory for the 11 Oct iQOO split: MISSING.

### 4.8 Missing measurements

- Same-prompt, same-split TCP vs WebRTC comparison; any WAN/TURN run; RPC byte totals per token.
- Prompt-reuse numbers on the split (follow-up question, 11 Oct 08:2x).
- Per-token time attribution (compute vs RPC wait vs bridge queue).
- Power draw (W) of any device under load or idle; the 1 W idle figure is an assumption.
- Battery wear over a sustained session; thermal behaviour of the 9B split.
- Same-hardware single-phone vs split decode for the 9B with identical prompts (row 27 vs 30 differ in
  output length: 160 vs 72 tokens).
- AnswerCheck on phones; Mesh Compiler runs on Qwen3.5-9B or the 30B; any GPU run inside the app.
- Pull-a-cable / Helper-loss mid-generation on phones; long-duration background survival.
- Laptop CPU model; iQOO 15 battery capacity (Wh).
- Quality on a harder benchmark (HumanEval+/MBPP+); Case Room answer-quality evaluation.

### 4.9 Contradictions between sources

1. **Hidden-state bytes.** Docs/app: n_embd × 2 B ("4 KB per token" for the 30B; `Gguf.kt:159`,
   `UNDERSTANDING.md` §2.2, `CLAIMS-LEDGER.md` marks it "derived — and correct"). llama.cpp CPU
   activations are F32 ⇒ likely n_embd × 4 B (8 KiB). Not measured either way.
2. **Topology.** `UNDERSTANDING.md` §2.5 draws Phone A → Phone B directly; `ggml-rpc.cpp:752-770` refuses
   cross-server copies, so activations route via the Host.
3. **RTT statistic.** `Planner.kt:9` says "average round trip", `MeshHost.kt:40` says "median of the last 10
   pings", the code (`MeshHost.kt:426`, commit `7d38cb5`) uses the **minimum**. The minimum is optimistic by
   design; the worst ping is shown but not gated.
4. **Line reference.** Docs cite `Gguf.kt:127` for `hiddenBytes`; it is at line 159 now.
5. **Capsule task times.** Capsules: agent baseline 95.7 s, winner 40.47 s. `03-results.md`: agent baseline
   45.4 s, GPU f16 29.4–35.6 s. Arithmetic shows the capsules use the 2,048-token prompt speeds (#49:
   2000/24.9 + 300/~19.5 ≈ 95.7 s; #52: 2000/108 + 300/~13.6 ≈ 40.5 s) while the table uses pp512 speeds.
   Both are internally consistent; they measure different prompt lengths and should not be quoted together.
6. **"28–43 % lower agent step"** (`branch-audit-2026-10-11.md:325`) does not match `03-results.md`, whose
   GPU rows show −20 % to −35 % (−77 % only for the 2k prompt-read time).
7. **Noise guard.** Mesh Compiler/export use > 3 %; on-phone Tune and learning use ≥ 5 %.
8. **Unit test count.** Roadmap/privacy docs: "all 112 Android unit tests passed"; this report counts 111
   `@Test` annotations in the working tree (another agent is editing `CaseCitationsTest.kt`; minor).
9. **Origami README** (`origami/README.md`, "Geometry and manufacturing limits") says there is no kerf
   compensation and no structural rating; commits `0f78c64` (kerf in SVG, beam check) and `95fc708` (wedged
   tenons) added them. The README is stale.
10. **"Mesh Compiler is not yet wired to the app"** (roadmap baseline text) vs `189d853` "the missing wire".
    Both are partly true: the wire exists in code; no capsule that changes a setting reaches a phone.
11. **First-word claim.** `UNDERSTANDING.md` §4.6 says the Aider first word "fell from 287 s to roughly 20";
    no measurement row records a first-word time for the mesh Aider runs ("—" in `measurements.md:24-25`).
12. **30B headline speed.** Deck "6.6" vs best runs 6.7–7.3 tok/s (`CLAIMS-LEDGER.md` already flags).
13. **Measurements header** says "every number here was run", but row 16 contains an extrapolation
    ("~13 min"), labelled as "would take".
14. **Qwen3.5-9B layers**: header `block_count` 33, runtime splits 0–31 (32). The extra block is the MTP
    (`nextn_predict_layers=1`) layer; not a conflict but easy to misread.
15. **TTFT vs server timing** diverged by up to ~18 s in the 5-run TCP series (`remote_validation.md:124`);
    unexplained.

### 4.10 Failed and void experiments

| When | What | Outcome | Source |
|---|---|---|---|
| 26 Sep 13:45 | split over venue Wi-Fi (373 ms avg) | not loaded after 60 s | `measurements.md:8` |
| 27 Sep 05:25 | 359-token prompt over Wi-Fi | 94 s to first word, cut off | `:22` |
| 7 Oct | heat-aware planner | reverted 28 min later | `a64516d`, `8869f2c` |
| 10 Oct | Mesh Compiler trials #23, #44 | context creation failed | `03-results.md` |
| 10 Oct | baseline confirm #45–46 | +40 % agent time from owner using phone | `03-results.md` |
| 11 Oct | WebRTC bridge attempts 1–2 | malformed RPC; zero bytes | `remote_validation.md:156-159` |
| 11 Oct | OCI public endpoint | TCP 443 connection refused; VNIC `NotAuthorizedOrNotFound` | `remote_validation.md:25` |
| 11 Oct ~08:05 | on-phone Tune | **void**: old llama-server held :8080, Tune timed the old process | `15962c6`, `measurements.md:29` |
| 26 Sep | early phone app under-reported memory by GBs | fixed (/proc/meminfo) | `8361268` |

---

## 5. Technical innovations, prior art and dependencies

### 5.1 What MeshAI actually adds

| Item | Novel? | Notes |
|---|---|---|
| Layer split across devices via llama.cpp RPC | **No** (llama.cpp feature) | `--rpc`, `-ngl`, `--tensor-split`, `--override-tensor` are upstream flags |
| Tensor hash cache | **No** (upstream `-c`, SET_TENSOR_HASH) | |
| Pre-filling the hash cache from each phone's own GGUF (`LayerStore`) | **Integration, useful** | Measured 437 s → 70 s; not seen in upstream docs; Pooled downloads per-device layers instead |
| Memory-proportional contiguous planner + reserves + RTT/battery gates + preflight | **Engineering** | prima.cpp's Halda scheduler is more sophisticated (co-optimises CPU/GPU/disk); MeshAI's planner ignores device speed |
| Phone as Host (llama-server on Android) with phones as Helpers | **Uncommon, not unique** | exo advertises Android/iOS; prima.cpp tested a phone node |
| TCP-over-WebRTC DataChannel bridge for unmodified ggml RPC | **Integration** | Pooled uses WebRTC with its own WebGPU engine; MeshAI tunnels llama.cpp's binary protocol, with backpressure and integrity checks |
| HMAC-signed per-Helper work orders with proposed-vs-applied | **Engineering** | metadata only; no attestation |
| Task-time-scored, quality-gated, cool-down-controlled Optuna tuning on a phone | **Integration** | method from llama-optimus (Optuna over llama-bench, MIT); new parts are task-time scoring, quality gate, phone run cards |
| Correctness measured on the split (identical failure set) | **Evidence, not mechanism** | The literature cited in `UNDERSTANDING.md` §7.4 rarely reports task accuracy for splits |
| Applications (Case Room with citation check and per-Helper consent; Origami generators with beam check and joints) | **Product work** | ordinary techniques (BM25-style search, regex dates, Euler–Bernoulli beams) |

The one research gap the team identified (per-device compiled artifacts emitted by a cross-device plan,
`UNDERSTANDING.md` §7.1–7.3) is **not occupied** by the current code: every Helper runs the identical
generic CPU `ggml-rpc-server`. The compiler capsule path is the first step towards it, but no non-baseline
artifact is applied.

### 5.2 Third-party dependencies

| Dependency | Version | Use | Licence |
|---|---|---|---|
| llama.cpp / ggml | 66fba63 | engine, RPC, server, multimodal (`libmtmd`) | MIT |
| Qwen model weights | as listed in §2.1 | models | Apache-2.0 for Qwen3 family (others not re-verified) |
| Google WebRTC (io.github.webrtc-sdk:android) | 150.7871.01 | DataChannels | BSD-3-Clause |
| OkHttp | 4.12.0 | signalling WebSocket | Apache-2.0 |
| PDFBox-Android (com.tom-roush) | 2.0.27.0 | PDF text | Apache-2.0 |
| ZXing Android Embedded | 4.3.0 | QR | Apache-2.0 |
| Filament + gltfio + utils | 1.71.4 | Origami 3D | Apache-2.0 |
| Jetpack Compose BOM / AndroidX / kotlinx-coroutines | 2025.06.01 / 1.10.x | UI | Apache-2.0 |
| Rust crates serde_json, libc, qrcode | 1 / 0.2 / 0.14 | agent | MIT/Apache-2.0 |
| ws (Node) | 8.22.0 | signalling | MIT |
| coturn | 4.6.3 image | TURN | BSD-3-Clause (New BSD) |
| Caddy | 2.9 image | TLS proxy | Apache-2.0 |
| Aider | 0.86.2 | coding agent | Apache-2.0 |
| Optuna (mesh-compiler) | — | search | MIT |

Licences are from general knowledge of these projects, not re-read from each package for this report
(PARTIAL). Model licences in particular must be checked per file before any commercial use.

---

## 6. Business feasibility

Not in this file by scope change: written separately in `docs/reports/meshai-v2-market.md` (to be merged).
Throughput inputs for it are in §4 (measured decode 6.6–7.3 tok/s for the 3-device 30B, 7.5–7.6 for the 2-phone 9B).

## 7. Competitive landscape

Not in this file by scope change: see `docs/reports/meshai-v2-market.md`. Repo-side prior-art notes are in §5.1.

---

## 8. Limitations and future work

| Item | State | What is needed |
|---|---|---|
| Two-phone and laptop+phone split over USB | **Completed** | repeat per release (roadmap stage 0) |
| Wi-Fi LAN split | **Partial** (2.2–2.5 tok/s with POCO Helper; iQOO↔iQOO Wi-Fi 7.26 tok/s 30B after RTT fix) | matched-prompt comparison with USB |
| WAN / TURN inference | **Failed (blocked)** / untested | OCI ingress for 443, 3478, 49160–49200; then Wi-Fi-to-cellular and relay-only runs; expect ≥ 1 RTT per boundary per token |
| WebRTC vs TCP | **Untested** (no matched run) | same prompt, split, output length |
| Heterogeneous devices | **Partial**: laptop+phones and iQOO+POCO work; placement ignores speed; POCO Helper run was 3× slower | measured per-device profiles into the planner (roadmap stage 7, "speed-aware split") |
| GPU/NPU acceleration | **Out of scope** for the app; GPU measured in Mesh Compiler (prompt 3× faster, decode slower) | OpenCL build in APK, RPC server `-d GPUOpenCL`, task-aware choice |
| FastPath (exchange whole stage activations instead of graph RPC) | **Planned** (`origami/final plan.md:144-156`) | profiling evidence that graph RPC overhead matters (instrumentation missing) |
| Mesh Compiler on the target models | **Missing** for 9B and 30B | runs on Qwen3.5-9B and the split |
| Prompt reuse with edits (`--cache-reuse`) | **Not enabled** in app | add flag; measure on split |
| Failure handling | **Partial** (one disclosed restart; no KV resume) | pull-a-cable test; idempotent retry protocol |
| Security | **Partial** | authenticated/encrypted RPC; bind Helper RPC to a tunnel; external review |
| Case Room | **Partial** | phone check of timeline/conflicts/progress; OCR; quality evaluation |
| Origami fabrication validation (organizer, table, bookshelf; tab-slot, wedged, finger joints) | **Missing** | cut and assemble one reference per template; measure joint fit and load (step stool flagged unsafe until tested) |
| Compute rental | **Planned / out of scope** | threat model, metering, settlement, legal review (roadmap stage 10 gate) |
| Business validation | **Missing** | customer interviews, price test, measured idle and load power |

---

## Open questions and validations needed

1. What is the real per-token RPC payload (bytes per boundary, F32 vs F16)? Instrument byte counters in the
   WebRTC bridge or on the relay; compare with n_embd × 4.
2. Why does prompt reading halve on the 9B USB split (35.8 → 18.6 tok/s) when bandwidth is ~20× sufficient?
   Measure per-batch RPC wait vs Helper compute.
3. Same-prompt, same-split TCP vs WebRTC; then WAN and TURN-only.
4. Does `minimum-of-10` RTT admit Helpers whose typical RTT is far above 60 ms? Record worst/median under
   load.
5. Why did ~550 MB cross USB during a stored-layer load of the 9B split?
6. Idle and load power (W) of an iQOO 15 holding a split, on wall power and battery; battery temperature
   and charge behaviour over hours.
7. Does the 30B still score 15/15 on a harder benchmark, and does a split change answers there?
8. Prompt-reuse speed on the split (the uncaptured 08:2x follow-up).
9. Mesh Compiler results for Qwen3.5-9B and the 30B; any CPU setting that beats the baseline by > 5 %.
10. AnswerCheck on phones; learning-from-use over a real week.
11. Helper loss mid-generation on the phones (pull-a-cable).
12. Fabrication of each Origami template; physical load test before the step stool is shown.
13. External security review of pairing, API key exposure on 0.0.0.0:8080, and Helper RPC binding.
14. Licence check for every model file intended for commercial use (Qwen2.5-VL-3B licence in particular).

---

## References

### Repository files (paths relative to `/home/prajwal/Documents/GitHub/maynards`)

- `CLAUDE.md`; `README.md`
- `docs/measurements.md` (lines 7–30); `docs/sustained-8b-iqoo.csv`; `docs/remote_validation.md`;
  `docs/roadmap-next.md` (working tree); `docs/data-flow.md`; `docs/privacy-and-use-cases.md` (working tree);
  `docs/branch-audit-2026-10-11.md`; `docs/UNDERSTANDING.md` (§2.2, §2.5, §2.6, §3.4, §4, §7, §9.3, §9.6, §10);
  `docs/research/CLAIMS-LEDGER.md`; `docs/mesh-compiler/{README,01-findings,02-implementation,03-results}.md`;
  `docs/mesh-compiler/capsules/*.json`; `results/REPORT.md`, `results/*.json`
- `android/app/src/main/kotlin/ai/maynards/mesh/brain/{Planner,Preflight,Router,TaskDetector,EngineArgs,Catalog,Compiler,CompilerCapsule,AnswerCheck,Capsule,Sense,Stats,LayerStore,Gguf,Runner}.kt`
- `android/app/src/main/kotlin/ai/maynards/mesh/mesh/{MeshHost,MeshClient,Wire,RtcPeer,DataChannelStream,TcpDataChannelBridge,RemoteSignaling}.kt`
- `android/app/src/main/kotlin/ai/maynards/mesh/engine/{Engine,Specs,Net,Offer}.kt`
- `android/app/src/main/kotlin/ai/maynards/mesh/ui/{CaseDocuments,CaseCitations,CaseTimeline,CaseConflicts,ConflictFinder,CitedAsk,CodeFiles,UseTab}.kt`
- `android/app/src/main/AndroidManifest.xml`; `android/app/build.gradle.kts`
- `agent/src/{main.rs,host.rs}`; `agent/Cargo.toml`
- `remote/{server.mjs,compose.yaml,package.json}`
- `scripts/{build-llama.sh,phone-usb-relay.py,link-watch.py,export-capsules.py,benchmark-mesh.py,bench.py}`
- `origami/README.md`; `origami/final plan.md`; `origami/app/src/main/java/ai/maynards/origami/core/{Organizer,Structure,Catalogue,Mesh3D,Exporters}.kt`
- llama.cpp source: `/mnt/storage/meshai/build/third_party/llama.cpp/ggml/src/ggml-rpc/ggml-rpc.cpp`
  (lines 36–87, 195–215, 248–256, 717–770, 1000–1060, 1455–1500), `ggml/include/ggml-rpc.h:9-11`,
  `ggml/include/ggml.h:222-229`
- Mesh Compiler DB: `/mnt/storage/meshai/tuner/runs.sqlite` (read-only)

### Commits cited

`dd209f3`, `42a4512`, `00d53d9`, `95fc708`, `0f78c64`, `ad7a9b2`, `7419b9d`, `3e81088`, `3c812a8`, `b9f6f56`, `7da5fbc`, `54d9364`, `df69946`,
`0d05deb`, `3690d12`, `15962c6`, `eaa8e05`, `189d853`, `6d85372`, `539002a`, `b566c92`, `26d0ec4`, `a1aac26`,
`26bfad4`, `c043ccc`, `91db6d5`, `fd3773e`, `f5325ad`, `151a64c`, `1cb6aa6`, `43c9946`, `e539842`, `7d38cb5`,
`ca42060`, `d6adde3`, `a64516d`, `8869f2c`, `8361268`, `64b568b`, `26704c9`, `ccb7003`; branches
`origin/antigravity/{ned-capsule,remote-relay,supervisor-recovery,metering,job-queue,speed-planner}`,
`origin/fixes/demo-hardening`.

### External (all accessed 2026-10-11)

- llama.cpp RPC docs: https://github.com/ggml-org/llama.cpp/blob/master/tools/rpc/README.md
- prima.cpp: https://arxiv.org/abs/2504.08791 ; https://github.com/Lizonghang/prima.cpp
- exo: https://github.com/exo-explore/exo ; https://www.cnx-software.com/2025/02/18/exo-software-a-distributed-llm-solution-running-on-a-cluster-of-computers-smartphones-or-sbcs/
- distributed-llama: https://github.com/b4rtaz/distributed-llama
- Pooled: https://github.com/Nehanth/pooled (via `docs/mesh-compiler/01-findings.md`)
- llama-optimus: https://github.com/BrunoArsioli/llama-optimus
- Qwen3-Coder-30B prices: https://pricepertoken.com/pricing-page/model/qwen-qwen3-coder-30b-a3b-instruct ;
  https://www.aipricing.guru/models/novita-qwen-qwen3-coder-30b-a3b-instruct/ ;
  https://modelcompare.dev/models/amazon-bedrock/qwen3-coder-30b-a3b
- GPT-5 mini prices: https://developer.puter.com/tutorials/openai-api-pricing/ ;
  https://anotherwrapper.com/llm-pricing/gpt-5-mini-2025-08-07 ;
  https://pricepertoken.com/pricing-page/model/openai-gpt-5-mini
- Salad: https://blog.salad.com/lowest-cost-gpus/ ; https://computeprices.com/providers/salad/gpus/rtx3090
- Vast.ai: https://computeprices.com/providers/vast/gpus/rtx4090 ; https://costbench.com/software/ai-gpu-cloud/vast-ai/
- io.net / Akash: https://io.net/p/cheapest-gpu-cloud-provider-2026-pricing-comparison-io-net-vs-aws-vs-azure ;
  https://www.buildmvpfast.com/blog/depin-gpu-inference-io-net-akash-below-aws-2026 ;
  https://akash.network/the-bid/h100-rental-price-2026-cost-per-hour/ ;
  https://computeprices.com/providers/akash/gpus/h100
- Acurast: https://acurast.com/?p=4732 ; https://decrypt.co/297978/acurast-unveils-processor-lite-for-ios-empowering-iphone-users-to-join-the-depin-cloud-rebellion-secured-by-polkadot
- Phone energy: https://arxiv.org/abs/2609.11940 ; https://arxiv.org/abs/2410.03613 ;
  https://arxiv.org/pdf/2606.23001 ; https://blog.greenspector.com/en/environmental-impact-of-local-ai/
- Battery cycles: https://www.phonearena.com/news/google-reveals-how-many-charging-cycles-a-pixel-battery-gets_id166537 ;
  https://stuff.co.za/2021/09/13/xiaomi-120w-charging-impact-phone-battery/ ;
  https://itdaily.com/blogs/werkplek/eu-smartphone-batterij-vergelijking
- Activation inversion (as cited in repo): https://arxiv.org/abs/2605.23158
