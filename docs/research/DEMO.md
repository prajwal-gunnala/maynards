# Stream 3 — what is actually demoable, mapped onto code that exists

Researched 7 October 2026 against `~/maynards` at `8986302`. Every claim here cites a file and line.

## 1. What the planner already does

`agent/src/host.rs:313` `fn plan(m, devs, ctx)`. It is a single greedy heuristic, not a search, but it
already implements more of the brief than the brief credits it with.

| Brief concept | Already in the code | Where |
|---|---|---|
| Live Device Profile (§4.1) | `Dev { usable, rtt, battery, charging }` built per poll | `host.rs:300` |
| Constraint filtering | helpers excluded for `rtt > 60 ms`, `battery < 20 && !charging`, or less memory than the biggest single layer — each with a human reason recorded in `skipped` | `host.rs:320-328` |
| "Reward saying *do not* distribute" (§6 key planner rule) | `verdict: not_possible` with "Short by N GB", and `"Runs on this laptop alone"` when the host suffices | `host.rs:331-334`, `347-350` |
| Task fingerprint, partially | `ctx_tokens()` reads `MESH_CTX`; KV cache is costed **per device per layer**, so 4096 and 16384 produce **different splits on the same hardware** | `host.rs:304-307`, `314-316` |
| Memory plan | per-slice byte budget, `HOST_RESERVE` 0.30 GB, `HELPER_RESERVE` 0.15 GB | `host.rs:310-311` |
| Model-shard affinity (§16 mitigation) | pinned splits in `~/.config/meshai/pinned.json`, validated against the model's real layer count so a stale pin cannot place layers that do not exist | `host.rs:366-380` |
| Quality/feasibility envelope | `verdict` = `doable` (15% headroom) / `tight` / `not_possible` | `host.rs:329` |

**The important one:** context length already changes the plan. That is task-conditioning with a
single input variable. The brief presents task-conditioned planning as unbuilt; one dimension of it
ships today and is measured — the 4096 runs and the 16384 Aider runs in `docs/measurements.md:18-25`
used genuinely different splits.

## 2. What is missing, precisely

- **No per-node artifacts.** Every helper runs the same `ggml-rpc-server` at the same quantization.
  There is no Node Capsule: no per-layer precision, no per-device kernel selection. This is the
  largest gap and the most expensive to close.
- **No candidate generation or scoring.** One greedy pass: phones take their full share from the last
  layer backwards, biggest phone gets the tail (`host.rs:336-346`). Never N candidates compared.
- **No performance predictor.** Nothing estimates tokens/s or TTFT before running. The brief's
  Compute Twin has no foundation in the code at all.
- **No cost objective and no cloud tier.** Nothing in the planner references money, and there is no
  cloud device class. The entire §11 savings story is unimplemented.
- **No closed loop.** The plan is computed and run; it is never revised while running.
- **Thermals are collected and then discarded.** See below.

## 3. The thermal gap — the best small increment we have

The phones already measure and transmit heat, and the planner ignores it.

- Phone side: `Specs.kt:38` puts `"heat"` into the JSON sent to the host every poll; read back at
  `Specs.kt:45`.
- Laptop side: `host.rs:146-150` reads the hottest `/sys/class/thermal` zone into `temp_c`.
- It reaches advice only as a warning string above 90 °C (`host.rs:920`), and **never reaches `Dev`**
  (`host.rs:300`), so it cannot affect a single placement decision.

Meanwhile `docs/sustained-8b-iqoo.csv` records throughput **halving** — 11.44 → 5.29 tok/s — as skin
temperature goes 34.6 → 47.5 °C over ten minutes. We have both the signal and the evidence that it
matters, and we use neither.

This makes a demo increment that is small, honest and directly on the brief's claim of a live-state
closed loop: derate a device's assumed throughput by its reported heat, and let the planner move
layers off a device that is cooking. Cost: a field on `Dev`, a term in the scoring, and a re-plan
trigger.

## 4. Recommended demo, in priority order

1. **Two named task profiles** — "short chat" (ctx 4096) and "long-context coding" (ctx 16384) —
   shown side by side producing **visibly different layer plans on the same three devices**. This is
   nearly free: `plan()` already does it, it just has no UI and no name. It is the single clearest
   demonstration of task-conditioned planning and it is backed by real runs already in
   `measurements.md`.
2. **Candidate plans, scored and listed.** Generate the handful the planner already implies — host
   alone, host + one phone, host + both, pinned — score each on fit and predicted throughput from our
   own measured table, and show why the winner won and why each loser lost. The `skipped` map
   (`host.rs:325`) already produces exactly the "why not" sentences this needs.
3. **Thermals in the plan**, as above, with the measured decay curve shown beside it.
4. **A plan comparison with a cloud column**, clearly labelled estimate, using the pricing the
   economics stream returns. No cloud account required and none claimed.

Items 1 and 2 need no new measurements and no phones to develop against. Item 3 needs a phone to
verify. Item 4 is presentation only.

## 5. The run contradiction — resolved

Five separate 30B runs exist in `docs/measurements.md:18-22`. The brief and the deck quote different ones.

| Run | Laptop share | Phones hold | tok/s | Cold start |
|---|---|---|---|---|
| 26 Sep 20:18 (line 18) | layers 0–17 | 6.19 + 4.49 = 10.68 GB | 6.6–7.1 | 70 s (first ever load 517 s) |
| 27 Sep 01:10 (line 20) | 0–22, 9.19 GB | 7.53 + 2.04 = **9.57 GB** | **6.6** | **70 s** ← **the deck, README, deck2** |
| 27 Sep 01:25 (line 21) | 0–8, **4.09 GB** | 6.41 + 7.33 = **13.74 GB** | **6.7–7.1** | **87 s** ← **the brief** |
| 27 Sep 05:28 (line 22) | 0–14 | — | **7.2 / 7.3** | 69 s; TTFT 1.1 s short, 11.6 s on 359 tokens |

**Recommendation: make 27 Sep 01:25 canonical, and correct the deck to match the brief — not the
reverse.** In that run the phones hold **13.74 GB of an 18.56 GB model, 74% of it**, and the laptop
only 4.09 GB. For any thesis about phones supplying the memory, that is the strongest run we own. The
deck currently publishes the run where the laptop carries 9.19 GB and the phones only 9.6 — it
undersells us by a wide margin.

The 87 s vs 70 s difference is immaterial and both round to "about a minute and a half"; do not let
it drive the choice.

Quote 05:28 separately and only for latency, where it is the best evidence we have: first word in
1.1 s over USB, against 94 s and cut off over venue Wi-Fi.

**Files to correct if 01:25 becomes canonical:** `docs/deck-editable/build.py` (and the byte-identical
`~/IQOO/deck3/build.py`) at lines 238, 334, 562-563; `README.md:21`; `docs/deck2/deck.html:201,506`;
`docs/deck/deck.py:470,555,614`. Note `build.py:562` says "The phones held 9.6 GB of it" directly
beside the 70 s figure — those belong to the same run and must move together or not at all. Mixing
13.74 GB with 70 s would be a new error, not a fix.

## 6. Two claims to fix before anything is presented

- **"17.8 GB aggregate" does not exist in the repo.** It is 4.09 + 6.41 + 7.33 summed, and it includes
  the laptop, so "device memory across three nodes" is quietly carrying the host. Say "13.74 GB on the
  two phones, 4.09 GB on the laptop" and show the addition, or drop it. Separate trap:
  `measurements.md:20` records "17.6 GB per phone" as a **disk** layer-store figure — anyone grepping
  for 17.x lands on that and concludes we cannot keep our numbers straight.
- **"4 KB per word" has no measurement.** It is a headline stat in the deck
  (`build.py:238,299,339`); the repo only says qualitatively that one activation crosses per token
  (`README.md:28,122`). It is `embedding_length × 2` bytes. Either label it a derivation and show that
  arithmetic, or measure the bytes on the wire. It currently sits beside two measured numbers, which
  implies it is one.
- **Stale hardware trap:** `docs/pre-event/handoff/02-state-and-numbers.md` was a different laptop
  (i3-7020U, 7.7 GB) and different phones (realme). Its 19.6–24.9 tok/s figures read as flat
  contradictions of the current table unless the reader notices the kit changed.
