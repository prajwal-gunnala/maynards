# Claims ledger

Every number we might say on stage, with its source and its status.
Status is one of **measured** (our hardware, recorded), **derived** (computed from a sourced input,
arithmetic shown), **cited** (someone else's published figure, verified against the source), or
**estimate** (neither — must be labelled as an estimate out loud).

Compiled 7 October 2026. Repo at `~/maynards` commit `8986302`.

## A. Our own measurements

| Claim | Status | Source | Note |
|---|---|---|---|
| Qwen3-Coder-30B-A3B-Instruct Q4_K_M, 18.56 GB, 48 layers | **measured** | `docs/measurements.md:18`; 48 layers confirmed against Qwen's official `config.json` (`num_hidden_layers: 48`) | Our 0–47 splits are consistent with the real architecture |
| Laptop 4.09 GB, phone ·8a75 6.41 GB, phone ·8373 7.33 GB (RSS) | **measured** | `measurements.md:21` (27 Sep 01:25) | The canonical run — see DEMO.md §5 |
| Phones hold 13.74 GB of 18.56 GB, i.e. 74% | **derived** | 6.41 + 7.33 from `measurements.md:21` | Show the addition. Strongest framing we own |
| "17.8 GB across three nodes" | **derived, and discouraged** | 4.09 + 6.41 + 7.33 = 17.83; the string `17.8` is nowhere in the repo | Silently includes the laptop. Prefer the 13.74 GB framing above. Beware `measurements.md:20` "17.6 GB per phone", a *disk* figure |
| 6.7–7.1 tokens/s sustained decode | **measured** | `measurements.md:21` | Deck currently publishes 6.6 from a different run |
| Cold start 87 s, split never used before | **measured** | `measurements.md:21` | Deck publishes 70 s from the 01:10 run |
| First load from disk 517 s → 70 s cached | **measured** | `measurements.md:18` | |
| Unstored split 437 s → stored 70 s | **measured** | `measurements.md:19,20` | The evidence that phone-resident layers matter |
| 7.2–7.3 tok/s | **measured** | `measurements.md:22` (05:28) | Our best throughput; quote only with its own run |
| First word 1.1 s on USB; 11.6 s on a 359-token prompt | **measured** | `measurements.md:22` | |
| Same 359-token prompt over Wi-Fi: 94 s to first word, then cut off | **measured** | `measurements.md:22` | Our strongest argument for the wired link |
| USB link 2–3 ms; venue Wi-Fi 266–483 ms (avg 373) | **measured** | `measurements.md:18`, `:8` | |
| USB model transfer ~24 MB/s (0.64 GB in ~27 s) | **measured** | `measurements.md:16` | |
| 18.6 GB would take ~13 min to transfer this way | **derived** | 18.56 GB ÷ 24 MB/s from `measurements.md:16` | Extrapolation, labelled as such in the repo |
| Accuracy: 0.6B 2/15 (13%), 1.7B 7/15 (47%), 1.7B split 7/15 (47%) | **measured** | `results/REPORT.md`, three JSONs in `results/`; harness `scripts/bench.py`, 15 tasks, temperature 0 | |
| The split failed **the same 8 tasks** as the single device | **measured** | same | Our best result, and stronger than we've said: independent failure at p≈0.467 would agree on all 15 with probability ≈3.3×10⁻⁵. **Lead with the identical failure set, not the score.** |
| "Splitting a model costs nothing in quality" | **measured — the MoE concern is now RESOLVED** | `results/REPORT.md`; the 30B row added by commit `64b568b` | **This row previously said the claim was mis-scoped because it was measured on a dense 1.7B while the flagship is MoE (`Qwen3MoeForCausalLM`, 128 experts, 8 active). That has since been measured directly: the 30B on laptop + 2 phones scores 15/15 (100%), median 6.8 tok/s. A perfect score cannot be degraded, so whatever routing does under splitting, every problem was solved. No further measurement needed.** |
| "15/15" or "100% accurate" | **measured, but a ceiling result — do not lead with it** | `results/REPORT.md` | A benchmark solved completely has stopped discriminating: it cannot separate a good configuration from a better one, nor show whether splitting costs anything, because there is no headroom left. A frontier API would also score 15/15. The durable result is the **identical failure set** on the 1.7B (same 8 problems split and unsplit; independent failure would agree on all 15 about 3 times in 100,000). Quote the *shape* — 13% → 47% → 100% — not the top number |
| "46.7% quality" or any absolute accuracy number | **measured but statistically weak** | `results/REPORT.md` | Wilson 95% CI for 7/15 is **[24.8%, 69.9%]**. n=15 is a demo, not a benchmark. Report the *equivalence*, decline the absolute number until ≥200 tasks or HumanEval+/MBPP+ |
| Phones are more energy-efficient than the cloud | **CONTRADICTED — never say this** | [arXiv 2609.11940](https://arxiv.org/abs/2609.11940) (verified): on-device averages **3× less** energy-efficient than batched server inference; our own throttling roughly doubles J/token (≈0.79 → ≈1.70 J/token), making it ~6× | Claim salvage-price hardware, data locality, and capacity where GPUs are unavailable — not efficiency |
| Sustained hourly throughput is ~50.7% of peak | **derived** | 11.44→5.29 tok/s from `docs/sustained-8b-iqoo.csv`; linear decay 10 min then steady = ~20,892 tokens/hour = 5.80 tok/s avg | Any capacity quote off peak is ~2× optimistic; sustaining peak aggregate needs 2.16× the devices |
| "We only send hidden states, not your text" (a privacy claim) | **CONTRADICTED — never say this** | ActInv, [ACM CCS'26 / arXiv 2605.23158](https://arxiv.org/abs/2605.23158) (verified): high-fidelity input reconstruction from split-inference activations, surviving Gaussian noise and sparsification; vec2text recovers 92% of 32-token inputs exactly | The mitigation is **node trust**, i.e. custody. We already keep `token_embd`/`output` on the host (`EngineArgs.kt:8`) — that is now a security feature |
| Battery wear is a meaningful cost per token | **CONTRADICTED — do not monetise** | ~167 M tokens/year per device at 5.29 tok/s, so a ₹2,000 battery is ~₹0.01 per million tokens | Real cost is maintenance labour and swelling/fire risk in racks. Free win: run racked devices at 40–60% SoC, which more than halves annual fade at 40 °C |
| Thermal decay 11.44 → 5.29 tok/s as skin goes 34.6 → 47.5 °C over ~10 min | **measured** | `docs/sustained-8b-iqoo.csv` (15 rows), summarised `measurements.md:15` | |
| Phone alone, Qwen3-8B, 11.8 tok/s | **measured** | `measurements.md:13` | |
| Dual-phone routing: text 12.3, vision 19.1 tok/s | **measured** | `measurements.md:17` | |
| Aider end to end: laptop 2.5 tok/s vs mesh 5.4 tok/s | **measured** | `measurements.md:23,24` | |
| Planner refuses a helper above 60 ms RTT | **measured** (code behaviour) | `agent/src/host.rs:321` | |
| **"4 KB per word crosses the cable"** | **derived — and correct** | `hidden_size: 2048` from Qwen's official `config.json` × 2 bytes = 4096 B = 4.0 KB; computed in code at `android/.../brain/Gguf.kt:127` (`hiddenBytes = embd * 2`) | **Earlier judged unsourced; that was wrong.** It is a sound derivation off the real header. Say "derived: hidden size 2048 × 2 bytes" and it is unassailable |

**Do not quote:** `docs/pre-event/handoff/02-state-and-numbers.md`. Different laptop (i3-7020U, 7.7 GB)
and different phones (realme). Its 19.6–24.9 tok/s figures look like contradictions of the table above.

## B. The brief's cited figures, verified against source

| Brief claim | Status | Verification |
|---|---|---|
| Vidur: "<9% inference latency error"; Vidur-Search found a LLaMA2-70B config in ~1 CPU-hour where exhaustive search would need ~42,000 GPU-hours / ~$218k | **cited — verified exact** | Abstract of [arXiv 2405.05465](https://arxiv.org/abs/2405.05465) states "less than 9% error", one hour on a CPU machine, 42,000 GPU hours, approximately $218,000 |
| Younesi et al. edge-cloud paper exists as cited | **cited — verified** | [arXiv 2512.23310](https://arxiv.org/abs/2512.23310) carries exactly the cited title; 1.4–2.8× latency, up to 41% energy, p95 down 53–61% vs cloud-only, on Jetson Orin NX and Raspberry Pi 5 |
| SWEET, Frontiers in Complex Systems 2026 | **cited — verified** | Accepted; first serving system to optimise layer-wise quantization bitwidth against a theoretical accuracy-degradation measure |
| **Citation hygiene problem** | — | **Two unrelated papers are named "Splitwise."** The famous one is Microsoft/UW at ISCA 2024, [arXiv 2311.18677](https://arxiv.org/abs/2311.18677), prefill/decode phase splitting, 1.4× throughput at 20% lower cost — which the brief describes *separately* under NVIDIA Dynamo. Cite [9] as "Younesi et al. (edge–cloud Splitwise)" and name the collision before a judge does |
| LLMCompass ~4.1% average end-to-end inference latency error | **cited — verified exact** | Abstract of [arXiv 2312.03134](https://arxiv.org/abs/2312.03134): "an average 4.1% error rate for LLM inference" |
| nn-Meter "public dataset of 26k DNNs" | **cited — verified, with a caveat to state** | [github.com/microsoft/nn-Meter](https://github.com/microsoft/nn-Meter) says **26k CNN models**, not DNNs generally, and its four targets (Pixel4 Cortex-A76, Mi9 Adreno 640, Pixel3XL Adreno 630, Movidius NCS2) are all 2019–21, pre-LLM. Kernel-level CNN latency prediction does not transfer to transformer decode. Say "26k CNNs" and own the gap — it supports the brief's own "extend to modern LLM" framing |
| prima.cpp 674 ms/token, 5–17× lower TPOT | pending | stream 1 agent is reading the full text |

## C. Estimates that must be labelled out loud

| Claim | Where it appears | Why it is an estimate |
|---|---|---|
| "A budget laptop about 4 GB, one 16 GB phone about 8.7 GB, three together about 20 GB" | `deck-editable/build.py:260-262` | Planner capacity arithmetic, not a measurement |
| "100 old phones ≈ 800 GB" | `build.py:525` | Business arithmetic; no measurement behind the per-phone figure |
| 8B ≈ 6 GB, 20B ≈ 13 GB, 30B ≈ 20 GB, 70B ≈ 45 GB | `build.py:532-533` | Model-size rules of thumb |
| gpt-oss 20B 12.1 GB, Qwen3 8B 4.7 GB | `build.py:252-253` | Model-card file sizes, not measured here |
| Any cloud cost or savings figure | not yet in any artifact | Nothing cloud-side has been measured; the economics stream will supply sourced pricing, still an estimate for *our* workload |

## D. Open items

- Decide the canonical run (recommendation: 27 Sep 01:25) and correct the six deck/README locations
  listed in `DEMO.md` §5.
- Restate or drop the 17.8 GB aggregate.
- Relabel "4 KB per word" as a derivation — do not retire it.
- Streams 1, 2 and 5 outstanding; this ledger gets a second pass when they report.
