# Positioning — the claim that survives

Stream 1 output, 7 October 2026. The three load-bearing 2026 papers were verified by me directly, not
taken on the agent's word:

- **[arXiv 2608.19147](https://arxiv.org/abs/2608.19147)**, 19 Aug 2026 — "Pre-Compiled Pipeline Shards
  for Distributed LLM Inference on Intel AI PC Fleets". Confirmed: *"a model is split by layer into
  per-stage shards, each pre-compiled into an OpenVINO graph, so that every machine runs one shard and
  passes activations to the next."*
- **[arXiv 2609.38697](https://arxiv.org/abs/2609.38697)**, 30 Sep 2026 — "Cascadia: A
  Control-Plane-Free Alternative to Hyperconverged AI Infrastructure". Real. Peer connections via
  certificates and gossip, three deployment modes, 3.10–4.06× throughput over single node.
- **[arXiv 2609.30270](https://arxiv.org/abs/2609.30270)**, **14 Jul 2026** — "HybridInfer:
  Thermal-Aware Reinforcement-Learning Tier Routing for On-Device, Edge, and Cloud LLM Inference".
  Real, and **inside the brief's own review window** — missed, not post-dated.

## The verdict

**The brief's novelty claim fails as written.** Five of its six components have named prior art, and
the sixth — the Node Capsule, the one the brief treats as its core — has an August 2026 paper that
implements it. The landscape cell reading *"task-conditioned per-node generated artifacts across
phone+laptop+cloud — fragmented / no dominant public end-to-end system found"* is **false**.

What survives is genuine but narrow, and worth building.

## The claim to make

Every clause is load-bearing. Drop one and a named system matches.

> We are not aware of a system that, from a single plan conditioned on a declared task fingerprint,
> emits a **different compiled execution package per node** spanning all three tiers — phone NPU/GPU,
> laptop/desktop, and **metered** public cloud — in which the layer cut points, the per-layer precision
> and the per-node backend kernel are **joint outputs of one cost-model-scored search**, and which
> **re-emits those packages** when live mobile telemetry (thermal headroom, battery, memory pressure)
> or cloud price crosses a threshold.

**Fallback if judges press hard** — this is the stronger move, because it concedes first:

> Our contribution is the composition. Existing work either compiles per-device artifacts with no
> cross-device plan (Olive, Qualcomm AI Hub, ExecuTorch, MLC, TensorRT), or plans across devices
> without emitting per-device artifacts (prima.cpp, Helix, EdgeShard), or routes across tiers on cost
> and thermals without compiling anything (HybridInfer, Perplexity). Cascadia is the only system we
> found that emits per-node compiled shards for a heterogeneous fleet — and it exports once at fixed
> INT4, uses "advertised resources, not a link-cost optimizer", and has no phone tier, no priced cloud
> tier and no thermal signal.

**Name Cascadia and HybridInfer before the judges do.** It converts the weakest point into evidence of
rigour, and a team claiming one defensible novelty beats a team claiming six and losing five on the
first question.

## The three closest systems, and the honest delta

### 1. Cascadia + Pre-Compiled Pipeline Shards — the most dangerous, and absent from the brief

Together they already do per-node compiled artifacts across a heterogeneous consumer-hardware fleet
with a live load/capability control plane. Cascadia even runs an exact branch-and-bound ILP *within* a
node, assigning stages across CPU, iGPU and NPU.

**Our delta:** it compiles the shard set **once, offline, at fixed INT4**, and its chain formation is
explicitly *not* optimised — the paper says deployment uses *"advertised resources, not a link-cost
optimizer"*. So we add (i) a cross-node search that *chooses* the cut points; (ii) precision and
kernel as search **outputs**, not fixed decisions; (iii) a phone tier — Cascadia is Intel AI PCs only,
no Android; (iv) a **priced** cloud tier; (v) thermal and battery state, which appear nowhere in
either paper; (vi) **re-emission** of artifacts on state change, versus export-once-route-forever.

Real delta. But "per-node compiled artifacts across a heterogeneous fleet with a live control plane"
is published, implemented prior art — not a gap.

### 2. prima.cpp / Halda — closest to MeshAI itself

Halda solves an NP-hard integer linear-fractional program over layer assignment, per-device CPU/GPU
split **and device selection** under RAM/VRAM limits, with OS-specific memory behaviour. Its testbed
includes a Redmi phone. It already re-plans: *"workloads can be repartitioned whenever the task queue
is empty to adapt to environmental changes"*, at 10–12 ms.

**Our delta:** prima.cpp is a **single uniform runtime** (20K LOC over llama.cpp) — no node gets a
distinct compiled package. **Quantization is a fixed input, not a decision variable** (Q4K held
constant across every experiment). The cost model is analytic and hand-derived, not learned. No cloud
tier in the objective, no monetary cost term. And decisively: **"thermal", "battery" and "throttl"
occur zero times in the 38-page paper.**

**What we must stop claiming:** "closed-loop re-planning on a heterogeneous consumer-device mesh
including a phone." Halda does that.

### 3. HybridInfer — kills a sub-claim outright

Thermal-aware RL routing across on-device / edge / cloud, using Android `getThermalHeadroom` and a
query-complexity estimate, with cost in the reward.

**Our delta:** it selects **which whole model answers the query** — it does not place layers. In the
full text "layer" appears once and **"partition" zero times**: no graph, no cut point, no memory plan,
no KV layout, no prefill/decode split. Three discrete tiers versus our combinatorial space of
execution graphs. Tabular Q-learning, not a predictor over candidate plans.

**What it kills:** that "phone + laptop + priced cloud in one objective, conditioned per request,
closed-loop on thermals, with cost in the objective" is novel. It is all in one paper, three months
before the brief.

### Critical runner-up — LLM-PQ ([arXiv 2403.01136](https://arxiv.org/abs/2403.01136))

*"We introduce adaptive mixed-precision into the search space of pipeline serving"* — jointly choosing
per-decoder-layer bitwidth, micro-batch size and model partition on heterogeneous clusters, scored by
a fitted linear-regression predictor, under a quantization-perturbation quality indicator, emitting
per-device configs derived automatically. **If we claim per-layer precision chosen inside a
cross-device placement search as novel, that claim is dead.** Its gaps: GPU clusters only, offline, no
phone, no thermal, no price.

## Claims to retire

| # | Claim | Defeated by |
|---|---|---|
| 1 | "No dominant public end-to-end system" for per-node artifacts across tiers | Cascadia + 2608.19147. Say instead: no phone tier, no priced cloud tier, no optimiser over cut points |
| 2 | Closed-loop **re-planning** from live device state is a differentiator | prima.cpp (repartitions on environmental change), exo (realtime topology), Cascadia (live LoadFrames), Neurosurgeon (2017). **Re-compilation** in the loop survives; re-planning does not |
| 3 | Per-layer precision chosen by search | LLM-PQ; NVIDIA ModelOpt `auto_quantize`; Olive `SelectiveMixedPrecision` |
| 4 | Learned predictor / "digital twin" for inference placement | TVM MetaSchedule (XGBoost cost model, years old), Vidur, Helix's own <5%-error simulator. The term "digital twin" is also taken |
| 5 | Per-device package = subgraph + kernels + memory plan | That is literally an ExecuTorch `.pte` (backend-specific, memory planning before serialisation), a QNN context binary, a TensorRT engine, an MLC model library |
| 6 | Cost-aware cloud tier selection | Mélange, 2502.00722, SkyPilot ("cheapest and available zone/region/cloud"), Perplexity's orchestrator |
| 7 | Thermals/battery as inputs to inference decisions | HybridInfer |
| 8 | Pooling heterogeneous consumer devices for one LLM | prima.cpp, exo, Cake, TPI-LLM, dllama, Cascadia |
| 9 | Power-aware backend selection | ONNX Runtime ships `MIN_OVERALL_POWER` and `MAX_EFFICIENCY` policies |
| 10 | Layer-granular device/cloud split with a learned predictor and live network state — **as an architecture** | **Neurosurgeon, ASPLOS 2017.** Device-class profile done once, regression models for per-layer latency and energy, then partition chosen per inference from live bandwidth and server load. Our pipeline *is* Neurosurgeon's pipeline with LLM-specific decision variables. Claim the variables, not the architecture |

## Two facts that help us defensively

- **Qualcomm AI Hub's own docs state "Mixed precision is not currently supported."** So per-layer
  precision search genuinely does not exist on the phone tier today. That is a real gap, narrow and
  true.
- **Android's hybrid-inference guidance concedes** that routing on network latency, battery, processor
  load and query complexity is left to the app developer — a first-party admission that the adaptive
  layer is unoccupied.

## Also already in print — cite it, don't let a judge produce it

**[arXiv 2609.23130](https://arxiv.org/abs/2609.23130)**, 19 Sep 2026, "From Inference Engine to
Inference Control Plane" — a position paper proposing *"an Inference Execution Planner that selects
feasible execution plans rather than only endpoints"*. The brief's thesis is already in print as a
research agenda. Citing it reads as command of the literature; being shown it reads as the opposite.

## Carried-forward uncertainty — do not put these on a slide

- **prima.cpp's ICLR 2026 venue is unverified.** arXiv metadata has no journal-ref. Helix's ASPLOS 2025
  *is* confirmed in its arXiv comment. Mélange has no venue in metadata. Say "arXiv 2025" for
  prima.cpp unless verified.
- **The survey question is unanswered.** Whether "task-conditioned per-node compilation" is already a
  named category in a published survey taxonomy was not resolved — the web-search budget ran out. If a
  survey has that cell populated, the landscape table weakens further. Worth finishing.
- **Forward-citation coverage is thin.** Semantic Scholar rate-limited (HTTP 429); only 3 citing papers
  retrieved for prima.cpp, none for Helix or Mélange. Cascadia was found through that partial list,
  which implies more remains undiscovered.
- **SOSP 2026 ran 29 Sep – 2 Oct 2026** and its proceedings were not audited. That is squarely inside
  the stale window.
- **Reported but unverified post-window hits:** ServeTwin (2610.02732, a benchmark-validated digital
  twin for distributed serving), **ThermE (2610.00267, contains a "Fast LLM-to-Heat Compiler" and a
  learned thermal-headroom predictor — lands directly on our thermal envelope)**, DySCo (2610.08268,
  runtime-reconfigurable edge/cloud layer-range splitting without weight reload). Check ThermE before
  presenting any thermal novelty.
- **Not opened in full text, second-hand:** DILEMMA 2503.01704, Parallax, EnerInfer, HexGen-2, Moirai,
  HeteroLLM, TPI-LLM, FlexGen, Llumnix, Mooncake, Sarathi-Serve, ServerlessLLM.
- **SkyPilot's price loop:** cheapest-zone selection and cross-cloud re-provisioning on preemption
  confirmed; continuous live-spot-price migration **not** confirmed. "Cloud price as a live re-planning
  signal" survives only in the narrow sense that nobody feeds price into *model-execution-plan*
  recompilation. Do not overstate.

---

## Addendum — "NVIDIA already does this." Checked: substantially true.

Raised by a teammate, verified 7 Oct 2026 against NVIDIA's own pages.

**NVIDIA has productised memory pooling across devices, at both ends of the scale.**

| NVIDIA product | What it pools | Interconnect | Result |
|---|---|---|---|
| **DGX Spark**, multi-unit | 2 × 128 GB coherent unified memory = **256 GB**; ConnectX networking supports **up to four units** | **ConnectX-7 @ 200 Gbps** (200G QSFP56 DAC) | NVIDIA docs state **"405B for dual-Spark configuration"**; the product page says 256 GB / "up to 400B". One unit alone ≈200B class |
| **NVLink / NVLink Switch** | 72 fully-connected GPUs in NVL72, *"effectively forming a data-center-sized GPU"*, functioning *"as a single high-performance accelerator"* | **NVLink 3 TB/s per GPU, 216 TB/s aggregate** | Datacentre-scale pooling for LLM and MoE inference |

So **"connect several devices, pool their memory, run a model too big for any one of them" is a shipping
NVIDIA product.** Any claim that we invented the *concept* is dead, and `DGX Spark` multi-unit should go
on the retire list alongside prima.cpp and exo (claim #8).

### What this does and does not take from us

**It validates the approach.** NVIDIA building two generations of hardware around pooled memory is the
strongest possible third-party confirmation that memory capacity — not compute — is the binding
constraint for running large models locally. That is our thesis, endorsed by the company with the most
to lose from it being wrong. Use it that way.

**What it leaves is the awkward case, and the gap is the interconnect.** Three differences, in order of
how much they matter:

1. **Bandwidth, by three orders of magnitude.** NVIDIA pools over purpose-built fabric: ConnectX-7 at
   200 Gbps ≈ 25 GB/s, NVLink at 3 TB/s per GPU. **We measured USB tethering at ~24 MB/s**
   (`measurements.md:16`). That is roughly **1,000× less than ConnectX-7** and ~10⁵× less than NVLink.
   This is the entire explanation for why they serve 405B at speed and we get 6.7–7.1 tok/s on 30B —
   and it is why our architecture sends **one 4 KB activation per token** rather than moving weights.
   Our contribution is making pooling work *at all* when the link is 1,000× too slow, which is a
   different engineering problem from theirs, not a worse attempt at the same one.
2. **Homogeneous purpose-bought hardware vs heterogeneous already-owned devices.** DGX Spark pairs two
   identical units someone bought for this purpose; NVL72 pools identical datacentre GPUs. We pool a
   mid-range laptop and two *different* Android phones that were already in people's pockets. NVIDIA
   has **no phone tier at all** — no Android, no handset.
3. **Capital per GB — verified, and the number moved sharply in our favour.** DGX Spark 128 GB is
   **$6,950** as of Oct 2026, having gone **$3,999 at launch → $4,699 (Feb 2026) → $6,950**, a **74%
   rise in under a year, explicitly attributed to LPDDR5x memory supply constraints**. (An earlier
   estimate of ~$3–4k in this document was wrong and is corrected here.) So:
   - 2 × DGX Spark = 256 GB for **$13,900 = $54.30 per GB** of fast unified memory
   - Salvage phones at ~$31 for ~8 GB usable = **~$3.88 per GB**
   - **≈14× cheaper per GB of capital, and ~1,000× slower per GB.** That trade is the honest statement
     of our position.

   **The price history is itself the strongest argument we have found for the thesis.** New memory is
   getting *more expensive*, not less, because of a physical supply constraint. Every month that
   LPDDR5x stays tight, memory already paid for and sitting in people's pockets becomes relatively more
   valuable. That is a tailwind nobody in the research corpus mentions, and it is verifiable from
   NVIDIA's own pricing moves.

4. **Bandwidth per unit, for honesty about where we lose.** DGX Spark's own memory bandwidth is
   **273 GB/s** (NVIDIA docs). Flagship phone LPDDR5X is roughly 68–77 GB/s — so one Spark has ~3.5–4×
   a phone's memory bandwidth *before* counting the 1,000× interconnect gap. Decode is
   bandwidth-bound, so this is the mechanism behind the throughput difference, not a tuning gap we
   could close.

### Consequence for the claim

The narrowed novelty sentence earlier in this document survives, because it was never about pooling —
it is about **re-issuing per-node packages from a live plan**, and nothing in DGX Spark or NVLink does
that. But the *framing* must change: we no longer say "nobody pools memory across devices." We say
**"NVIDIA pools memory across devices it sells you, over fabric it builds; we pool memory across
devices you already own, over links that are a thousand times slower."**

### Consequence for the business

This is the sharper implication, and it cuts. A developer who wants to run a large open model locally
has a clean, supported, fast option: buy one or two DGX Sparks. That is a direct competitor to renting
pooled phone memory, it is faster by orders of magnitude, and it comes with a support contract. The
phone farm's only remaining edge is capital per GB — and §2.2 of `FINDINGS.md` already shows the
per-token economics lose by 3.4× even before this comparison. **Treat DGX Spark as the benchmark the
rental business has to beat, and be honest that on everything except $/GB of capital, it does not.**

---

## Addendum 2 — corrections that arrived after this file was written

**This file is superseded by `UNDERSTANDING.md` wherever the two disagree.** It is kept as the working
record of how the position was reached. Three things landed after it was written and change its conclusions.

### 1. Voltron — the paper that defeats most of the novelty sentence above

[arXiv 2607.07046](https://arxiv.org/abs/2607.07046), 8 July 2026, Cho et al. *Verified in full text.*
Targets *"multiple user-end devices available at the edge"* — phones and tablets. Verbatim:

> *"At the beginning of each conversation turn, Voltron determines a model execution plan that specifies
> the layer-wise parallelism strategy and precision configuration, to maximize accuracy while satisfying
> QoS requirements under the current execution environment."*
>
> *"During the LLM execution, Voltron continuously monitors execution environment, and elastically adjusts
> the execution strategy by adapting to the runtime variance."*
>
> *"The computation time is estimated exploiting a regression model (of which average accuracy is 90%)."*

So a plan re-derived **per conversation turn**, over layer placement **and** per-layer precision jointly,
scored by a **learned predictor**, with a **continuous live-adjustment loop**, under a quality constraint —
four of the six claimed novelties, in one paper, three months before the brief was written.

**Verified absent from it:** "thermal", "temperature", "price" and **"compile"** do not appear anywhere,
and there is no public cloud tier in its objective. It emits a *plan*, never an *artifact*.

### 2. Retraction — the cloud-price axis is not open

The novelty sentence earlier in this file leans on live cloud price as an unoccupied signal. **That was
wrong.** Two systems do it:

- **ShuntServe** ([arXiv 2606.18600](https://arxiv.org/abs/2606.18600)) — *"a roofline model-based
  analytical serving performance estimator and a dynamic programming-based model placement optimizer that
  jointly determines node configuration, parallelization strategy, and layer assignment"* on heterogeneous
  **spot** clusters, reacting to interruptions and volatile availability. A live market loop driving
  **layer-level** placement.
- **HybridFlow** ([arXiv 2512.22137](https://arxiv.org/abs/2512.22137)) — *"routes each subtask online to
  the edge or cloud via a learned benefit-cost utility model that dynamically trades accuracy gains against
  token/API and latency budgets."*

With SkyPilot and Perplexity's orchestrator, cost- and price-aware adaptive placement is thoroughly
occupied. **Retire it.**

### 3. The revised claim, and what it rests on

Everything except **compilation** has fallen. The honest claim is a compiler claim, not a planner claim:

> Prior systems either plan across heterogeneous devices without emitting anything (Voltron re-derives a
> layer-wise placement and precision plan every conversation turn from live state using a learned
> predictor, but never compiles; prima.cpp and Helix schedule a uniform runtime), or compile per-device
> artifacts with no cross-device plan (Olive, Qualcomm AI Hub, ExecuTorch, MLC, TensorRT), or pre-compile
> per-node shards once and then only route (Cascadia). **No published survey taxonomy has a category for
> the join.** We are not aware of a system in which the output of the cross-device search is itself a set
> of compiled per-node packages that are **re-emitted** when live device state changes.

Load-bearing words: **"re-emitted"** and **"compiled."** Without a demonstration of re-*compilation* in the
loop — not re-planning — there is no defensible mechanism claim, and the honest repositioning is as an
integration contribution.

**Two important qualifications on that**, both established later:

- **It cannot be built on this stack.** `llama-server` fixes the split at launch via `--rpc`, `-ngl` and
  `--tensor-split`; changing it requires a restart of 70–87 s. Re-emission *during* a run is closed.
- **MeshAI is on the far side of the gap, not halfway across it.** The layer store caches *every* layer on
  *every* phone (*"Store every big layer of these models"*, 17.6 GB, 144 tensors) — deliberately the
  opposite of a per-device package, because it is what makes any split start in 70 s rather than 437. And
  every phone runs the identical generic `ggml-rpc-server` with identical settings: no per-device backend,
  thread, kernel or memory-layout choice at all.

### 4. Pooled — the closest system anywhere, and it took the browser idea

[github.com/Nehanth/pooled](https://github.com/Nehanth/pooled), `pooled.run`, by Nehanth Narendrula
(formerly SwarmLLM). *An MIT affiliation was mentioned in conversation but appears nowhere in the project;
treat as unconfirmed.*

Peer-to-peer LLM inference **in the browser**: devices open a URL, each holds some layers, hidden states
(*"4–10 KB per token"*) cross over **WebRTC** with a TCP/TLS relay fallback. Each device downloads **only
its own layers**, optionally from a peer in the room that already has them. WebGPU with custom WGSL
kernels — it does not use llama.cpp at all, which is how it escaped the raw-socket problem. Ships a coding
agent ("Code mode"). Qwen 3.8 27B (~17 GB) and Qwen 3.6 35B MoE (~22.5 GB).

**What it takes:** zero-install any-OS joining; per-device layer download (closer to the open gap than our
cache-everything design); a coding agent on the mesh; and independent confirmation of the whole
architecture, down to the hidden-state size.

**What it leaves:** its headline speeds come from an **NVIDIA GB10** (the DGX Spark chip, $4,000–7,000),
not phones; its own note is that *"Safari on a Mac reloads the tab under memory pressure when it holds most
of the 27B"*, which is the browser-tab suspension problem; it reports **no accuracy figure**; its agent
*"reads its own console errors, and fixes them"* — self-assessed rather than externally tested; and it
concedes *"hidden states are not private against a determined peer."*
