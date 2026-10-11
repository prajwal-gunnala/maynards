# MeshAI V2: business and numbers (from existing repo research only)

No new market research. Sources: `docs/research/FINDINGS.md` (streams 2 and 5), `POSITIONING.md`,
`CLAIMS-LEDGER.md`, `UNDERSTANDING.md`, `docs/measurements.md`. Technical detail is in `meshai-v2-report.md`.

## Measured (docs/measurements.md)
| What | Number |
|---|---|
| 30B coder across laptop + 2 iQOO, USB | 6.6–7.3 tok/s; phones held 13.74 of 18.56 GB |
| Coding benchmark, 15 problems | 15/15 (laptop alone: 1.7B 7/15, 0.6B 2/15) |
| Aider task end to end | 7 min 34 s, 5/5 tests |
| Split start, before vs after stored layers | 437 s → 70 s (first load from disk 517 s) |
| One phone, 8B, 10 min sustained | 11.4 → 5.3 tok/s (thermal) |
| 9B two-phone USB split (11 Oct) | decode 7.5–7.6 tok/s; prompt read 18.6 vs 35.8 on one phone |
| On-phone Tune | 6 threads 7.6 vs 4 threads 7.1 tok/s; kept default |
| USB-only proof | 218 connection samples on USB, 0 on Wi-Fi |

## Economics (FINDINGS stream 2; research figures, not measured by us)
- Phone tier loses on cost per token: **$0.403 vs $0.1201 per million tokens** (≈3.4×). Battery wear, not electricity, is the binding cost.
- Phones are about **3× less energy-efficient** than batched servers. Sustained throughput is ~**50.7%** of peak.
- Supply-side benchmark: Acurast pays **$1.34 per device per month**. A gainshare model does not close.
- DGX Spark is the benchmark a rental business must beat; it wins on everything except capital per GB.

## Where it can sell (POSITIONING / FINDINGS stream 5)
- Niche: **regulated / sovereign on-prem** (legal Case Room, private code), priced per accelerator, using devices already owned.
- Not: a consumer phone-compute rental marketplace.

## Claims: keep / retire
- Keep: "a 30B model no single device here holds, running across devices you own, offline, cited answers."
- Retire: "novel planner", "cloud-price-aware placement" (ShuntServe, HybridFlow), "closed-loop re-planning with a phone" (prima.cpp/Halda), "per-layer precision in placement" (LLM-PQ, Voltron).
- Privacy caveat: activation inversion (ActInv) means hidden states are not private against a determined Helper; say "stays on your devices", not "private from peers".
- Prior art to name first: Cascadia, prima.cpp, exo, Voltron, Pooled, Neurosurgeon, HybridInfer.
- Defensible: the integration (stored layers from each phone's file, memory-proportional split, signed per-Helper capsules, quality-gated tuning, USB/WebRTC links).

## Status
- VERIFIED: 30B split, 15/15, stored layers 437→70 s, USB-only split, signed capsules applied, Case Room citations.
- PARTIAL: WebRTC (one run, 2.22 tok/s), Mesh Compiler (no active speed-up in app), Preflight/AnswerCheck (unit tests only).
- FAILED: venue Wi-Fi splits, heat-aware planner (reverted), OCI WAN endpoint (port 443 refused), one void Tune run (fixed 15962c6).
- PLANNED: rented compute, GPU/NPU in app, cable-pull test, power (watts) measurement.
- MISSING data: power draw, laptop CPU model, battery capacity, prompt-reuse numbers on the split.
