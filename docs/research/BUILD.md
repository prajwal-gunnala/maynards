# The build — what the product is, and what we actually make next

Written 7 Oct 2026, reconciling stream 1 (prior art) and stream 5 (risk) against the code at `8986302`.

## 1. The idea, precisely

Two jobs exist in the literature and nobody joins them:

- **Planning** — which device holds which layers. Crowded: Voltron, prima.cpp/Halda, Helix, EdgeShard.
- **Packaging** — building the artifact a specific device runs. Also crowded, separately: Olive,
  Qualcomm AI Hub, ExecuTorch, MLC, TensorRT, and Cascadia's pre-compiled OpenVINO shards.

**The join is empty.** No system makes the per-device packages *the output of the cross-device plan*,
and no system **re-issues** them when live device state changes. Eight 2024–26 survey taxonomies have
no category for it; three explicitly name it as future work. That is the product.

The one-line version: **the plan's output is the packages, and the packages are re-issued while the
model keeps running.**

## 2. What we already have (the doc undercounts this badly)

| Capsule component (brief §4.4) | Status today | Where |
|---|---|---|
| Model fragment — only the assigned layers | **Built.** Layers extracted from the phone's own GGUF, hash-verified (`blk.40.ffn_down_exps = 94a16f7ad603732b`) | layer store; `LayerStoreTest.kt` |
| Memory plan — KV budget, reserves | **Partly.** Per-slice byte budget, `HOST_RESERVE` 0.30 GB, `HELPER_RESERVE` 0.15 GB, KV costed per device per layer | `host.rs:310-316` |
| Network policy — RTT limits | **Partly.** Helpers refused above 60 ms | `host.rs:321` |
| Safety envelope — battery | **Partly.** Refused below 20% unbatteried | `host.rs:322` |
| Safety envelope — thermal | **Collected and discarded.** Phones send `heat` every poll; never reaches `Dev` | `Specs.kt:38` vs `host.rs:300` |
| Task conditioning | **Partly, on one axis.** Context length changes the plan because KV is costed per layer | `host.rs:304-307` |
| Head/embeddings pinned to host | **Built** — and per stream 5 this is now a *security* property, not just a memory one | `EngineArgs.kt:8` |
| Precision per node | **Not built** | — |
| Backend kernel per node | **Not built** | — |
| The manifest itself | **Not built** — it is all implicit | — |
| Re-issue on state change | **Not built** | — |

Cold start 437 s → 70 s (`measurements.md:19,20`) is the evidence the fragment half works.

## 3. Honest constraint: we are not a compiler, and should not claim to be

Cascadia compiles **OpenVINO graphs**. Our capsule is a *staged* artifact — layer tensors plus
configuration — not a compiled binary. Calling it a compiler invites a comparison we lose.

**Per-layer mixed precision is also much harder for us than the brief implies**, for two independent
reasons:

1. **Storage.** Our layers are Q4_K_M tensors from one GGUF. A different precision per device needs a
   requantised copy of those tensors; requantising well wants F16 input. Holding variants means
   multiples of 18.6 GB per phone. Not viable at our scale.
2. **It destroys our only quality proof.** Stream 5: layer sensitivity varies by orders of magnitude
   and early-layer error compounds (LLM-PQ, MXSens). And our flagship is **MoE — 128 experts, 8 active
   per token** — where ~1e-3 gate drift flips which expert runs. Our 7/15 = 7/15 identical-failure
   result was measured on a **dense** 1.7B at uniform precision. Mixed precision would make the
   quality claim unmeasured at exactly the moment we lean on it.

**So precision is deliberately out of Tier 1.** This is not a retreat; per stream 1, LLM-PQ already owns
precision-inside-placement-search, so it was never going to be our novelty anyway. Both streams point
the same way, which is a good sign.

## 4. What we build, in order

### Tier 1 — the hinge, uniform precision (this is the contribution)

1. **The capsule manifest.** A declarative JSON per node: assigned layer range, backend, KV budget,
   memory reserve, RTT ceiling, thermal ceiling, telemetry schema. Start by *codifying what is already
   implicit* — this is mostly serialising decisions `plan()` already makes, so it is cheap and it makes
   the architecture legible. The brief's Appendix A manifest is a good shape; our fields differ.
2. **Thermals into the plan.** `heat` already arrives. Add it to `Dev`, and replace the implicit
   "ignore it" with a throughput derate: `cost_per_token = (amortisation + power·W) / throughput(heat)`.
   Calibrate the derate off `docs/sustained-8b-iqoo.csv` — we measured 11.44 → 5.29 tok/s, so we have a
   real curve rather than a guess.
3. **Re-issue on state change.** The hinge — and the one place to be careful about what is possible.
   `llama-server` fixes the split at launch (`--rpc`, `-ngl`, `--tensor-split`, `host.rs:727-732`), so
   **layers cannot move while a run is in flight**; re-issuing means restarting the engine, ~70-87 s.
   That is acceptable *between* agent tasks and unacceptable mid-token, which is why the agent workload
   is the right frame for this feature. **Partly built 7 Oct: heat now changes the plan before it starts
   (see `AGENT.md` §6).** What remains is triggering a re-plan automatically between tasks.
   *Nobody has published the re-emission step.*
4. **Backend per node** (Vulkan vs CPU) as a manifest field — cheap, real, and a second axis of
   per-node differentiation that costs no storage.

### Tier 2 — only after Tier 1 is measured

5. Per-layer precision, gated behind a per-plan quality certificate from an offline sensitivity
   profile. Never emit a mixed-precision plan that has not been evaluated.
6. A learned predictor over candidate plans. Note the brief's "digital twin" framing is taken, and
   Voltron already fits a regression model at 90% accuracy — so this is table stakes, not novelty.
7. A priced cloud tier. Stream 1 retired cost-aware cloud selection as novelty (Mélange, ShuntServe,
   SkyPilot, HybridFlow), so build it for completeness and utility, not for the claim.

## 5. The demo that shows the contribution

One screen, three devices, one continuous run:

1. 30B running across laptop + two phones, phones holding **74% of the model** (13.74 GB of 18.56 GB —
   the 27 Sep 01:25 configuration, which is our strongest and is **not** what the deck currently shows).
2. Load one phone until it heats. Show `heat` rising in the live telemetry we already collect.
3. The planner re-scores, **re-issues the manifests**, and layers move off the hot phone — while the
   answer continues streaming.
4. Show the two manifests side by side, before and after, and the measured tok/s through the transition.

That is the empty cell in the literature, demonstrated on real hardware, and every component except
step 3 already exists.

**Secondary demo, nearly free:** the same three devices under "short chat" (ctx 4096) versus
"long-context coding" (ctx 16384), producing visibly different layer plans. `plan()` already does this;
it has no name and no UI. Backed by real runs in `measurements.md:18-25`.

## 6. The one measurement to run before anything else

Re-run the 15-task harness on **Qwen3-Coder-30B-A3B**, split versus single device, logging per-layer
**expert-selection agreement** alongside pass/fail. Our quality claim is currently measured on a dense
1.7B while we demo an MoE 30B. If it comes back identical, the weakest claim becomes the strongest. If
it does not, we must know first. Needs the phones.

Also: stop quoting 46.7% as an accuracy number — Wilson 95% CI on 7/15 is [24.8%, 69.9%]. Report the
*equivalence*, not the score.

## 7. What the product is, in three layers

- **MeshAI (built, measured)** — pools memory across heterogeneous devices so a model runs that fits on
  none of them. Distinctive fact: **phones carry the majority of the model**, which no published system
  does; elsewhere the phone is an auxiliary node beside a real GPU.
- **The capsule layer (next)** — per-node packages issued and re-issued from a live cross-device plan.
  The research contribution.
- **The business (deferred, at your instruction)** — renting pooled memory. Economics stream pending.
