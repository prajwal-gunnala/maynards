# MeshAI branch audit — 2026-10-11

This is a source review of the local checkout and fetched `origin/*` refs. No branch was checked out, merged, or cherry-picked; the uncommitted working tree was preserved. Source review does not establish that a feature runs on the phones.

## Branch shape

`origin/antigravity/*` is an incremental chain based on `origin/main` (`8e0eaec`), with `origin/antigravity/speed-planner` at its current tip. The local `main` (`43c9946`) is one commit ahead of `origin/main` and contains the newer local WebRTC RPC checkpoint. `origin/fixes/demo-hardening` is an earlier ancestor of `origin/main`.

| Branch / commit | Feature | What the source contains | Main limitation found |
|---|---|---|---|
| `origin/antigravity/ned-capsule` `e5c6ce7`, `f9ad376` | Ned Capsule | JSON control message with model, layer range, runtime settings, evidence fields and an acknowledgement ID | It is metadata, not a model/layer artifact. Evidence run IDs are hard-coded; `limits` are not serialized; helper startup consumes backend/threads but does not apply all declared runtime fields. No device end-to-end verification is included in the branch tests. |
| `origin/antigravity/remote-relay` `ec5bc94`, `15702b3` | TCP relay and enterprise/security plan | Python relay splices raw TCP streams after a registration token; Android/Rust clients have optional relay wiring | The relay has no TLS, logs the full registration key, and binds publicly by default. It is not safe as a public relay. The enterprise document contains aspirations and claims beyond the implementation. |
| `origin/antigravity/supervisor-recovery` `8231b33` | restart after helper departure | Android Host re-plans and restarts the model if a helper leaves; laptop Host captures request bytes and attempts a replay after reload | Android recovery cannot restore the old KV/session. Laptop replay is detached from the original HTTP stream and is not evidence the original client receives a transparently resumed answer. Treat as restart/retry prototype, not transparent fault tolerance. |
| `origin/antigravity/metering` `8a5681b` | residency and token cost display | Counts RAM time/tokens and compares hard-coded phone/cloud price rates | These are assumed rates, not a market quote, payment ledger, or actual savings. The phone-side calculation accumulates elapsed seconds against a moving total, so it needs accounting review before any monetary claim. |
| `origin/antigravity/job-queue` `6e781cf` | persistent laptop job queue | Queues prompts, streams to the local model endpoint, stores progress/results and labels a job `near` or `far` | Both modes currently target the laptop's `127.0.0.1` inference endpoint; there is no far-job worker placement or rented-memory service. Queue persistence stores prompts/results in plaintext and the activity feed includes prompt text. This conflicts with a privacy-first developer story until redesigned. |
| `origin/antigravity/speed-planner` `9753731`, `2b36def`, `fa8e65f` | compute-aware ordering and runbook | Adds a `speedScore` heuristic and sorts eligible devices by free memory × score | Scores are hard-coded by chip/name substrings, not per-device measured task profiles. It is a heuristic, not the Mesh Compiler's measured optimizer or an adaptive scheduler. |
| `origin/fixes/demo-hardening` `92ca056` | repeated review action and web visibility | Small changes to laptop review flow and web UI | Historical work already present in `origin/main`; no separate feature to merge. |

The branch verification scripts are useful scaffolding, but several assert sample JSON or manually mutate in-memory job dictionaries. They do not compile the Android APK, exercise the Rust implementation end to end, or verify physical device behavior. The capsule guide describes on-device steps; it is a procedure, not a recorded result.

## What “capsule” means here

The branch's Ned Capsule is a structured **execution instruction**: model identity, layer bounds, settings, rationale, and an ID. It does not package weights or send a new model to a device. The current app's `LayerStore` precomputes exact GGUF tensor hashes locally on each Helper; llama.cpp RPC then uses those cached tensors when model layers are assigned. On a cache miss, the RPC protocol can still transfer tensor data. The capsule branch should be treated as a promising control-plane contract, not as a verified artifact installer/compiler.

## What the Mesh Compiler currently proves

The separate `../mesh-compiler` repository contains an Optuna tuner that benchmarks CPU/OpenCL settings on an iQOO 15, compares task time, runs a coding-quality gate, and measures prefix reuse. Its Qwen3-4B results report roughly 3× prompt processing and 28–43% lower coding-agent step time for a GPU/f16-cache configuration, with the same 14/15 coding gate; normal chat did not benefit. These are real, device-specific measurements recorded in that repository.

The tuner does not currently build/deploy a signed per-device package into the Android runtime. The per-task manifest/predictor, structural model changes, and general task compiler remain planned in its documentation. Do not present its 4B single-phone results as a speedup already applied to the two-phone 9B runtime.

## Current checkout behavior relevant to the pitch

- The Android planner estimates model weights, q8 KV by layer, fixed recurrent state where applicable, host/helper reserves, optional projector bytes, RTT and a low-battery gate. It uses a default 4,096 context and 15% memory spare target. It does not yet pick a model by task quality tests or use measured per-phone throughput to optimize placement.
- The use-case router chooses the largest **installed** model that fits; vision additionally requires its matching projector. It does not silently download a model. Task-specific CPU/GPU/cache profiles are not yet loaded from the Mesh Compiler.
- The current runner deliberately stops if a Helper disappears. It does not resume the in-flight generation. The experimental supervisor branch does not preserve Android KV state either.
- QR pairing remains in the Mesh tab. It issues pairing credentials; it does not attest that a peer is uncompromised.
- Spider Sense telemetry shows link/device state and native RPC graph events. A `graph_recompute` log is evidence an RPC command reached a Helper, not a completed-kernel timing or accelerator-utilization measurement.
- The laptop panel is an operator/controller and can show status; it is not required to execute model layers. Public TURN/WAN inference remains unverified in `docs/remote_validation.md`.

## Merge recommendation

Do not merge the Antigravity tip wholesale. Keep the useful directions and implement them against the current WebRTC-aware tree in separate, reviewable slices:

1. Define and validate a versioned capsule schema; derive settings from actual measured profiles; remove fabricated evidence; enforce model hashes, bounds, expiry and peer/session binding.
2. Keep public RPC behind authenticated encrypted transport. Do not deploy `scripts/relay.py` publicly as written; continue with the WebRTC/TURN path and security review.
3. Build recovery as an explicit request-level retry protocol with idempotency, cancellation, KV-loss disclosure and client-visible retry status. Never imply an existing token stream resumed when the model actually restarted.
4. Add real task-queue routing only after defining storage encryption/retention and distinguishing local queued jobs from remote workers. No prompt should be written to an activity log.
5. Show cost/rental data as clearly labeled estimates only after choosing auditable rates and a real consent, accounting and settlement model.
6. Feed benchmarked per-device profiles into planning and compare held-out results. Keep unmeasured speed scores out of “optimizer saved X%” claims.

