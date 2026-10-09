# Findings

Research for the iQOO finale, 7 October 2026. Streams as numbered in the plan.
Streams 1 and 2 (prior art, economics) are still running and will be appended.

---

# Stream 5 — risk, trust and physical limits

Two load-bearing citations were spot-checked by me directly and both hold:
[arXiv 2609.11940](https://arxiv.org/abs/2609.11940) is real ("The Battery Price of edge AI") and does
say on-device inference averages **3× less energy-efficient than batched server inference**;
[arXiv 2605.23158](https://arxiv.org/abs/2605.23158) is real, accepted to **ACM CCS'26**, and does
claim high-fidelity input reconstruction from split-inference activations that survives Gaussian
noise and activation sparsification.

## 5.1 The most serious problem in the submission: our headline model is MoE, our proof is dense

**Independently verified by me.** Qwen's official config for Qwen3-30B-A3B is
`Qwen3MoeForCausalLM`: **128 experts, 8 active per token**, `hidden_size` 2048, 48 layers.

Our quality-neutrality result — 1.7B scoring 7/15 single-device and 7/15 split, failing the *same 8
tasks* — was measured on a **dense** 1.7B (`results/laptop-alone-1-7b.json`,
`results/laptop-laptop-helper-1-7b-split.json`).

In an MoE, numerical drift does not stay numerical. A gate-score perturbation of ~1e-3 near the
top-k cutoff changes *which expert runs*, and the divergence compounds across layers
([Fireworks, MoE numerics](https://fireworks.ai/blog/when-faster-not-identical-moe-numerics)).

**So the claim "splitting costs nothing in quality" is evidence about dense layer-splitting and is
not evidence about the 30B we actually demo.** This is the single thing most likely to be exposed by
a judge who knows the architecture, and it is fixable only by measurement.

**The one measurement worth doing before the finale:** re-run the 15-task harness on
Qwen3-Coder-30B-A3B, split versus single-device, logging per-layer **expert-selection agreement**
alongside pass/fail. Identical result converts our weakest claim into our strongest. A different
result we need to know before a judge does. Needs the phones.

## 5.2 The quality claim is stronger than we've been stating — once scoped correctly

The useful statistic is not 7/15 = 7/15, it is the **identical failure set**. Two systems of equal
true quality (p ≈ 0.467) failing independently would agree on all 15 items with probability
≈ **3.3 × 10⁻⁵**. That is not coincidence; it is evidence the split produced the same token stream.
**Lead with the identical failure set, not the score.**

Theory supports it under precise conditions: layer-wise splitting does not change reduction order
*inside* a layer, it moves a tensor across a boundary. Production nondeterminism comes from
batch-size-dependent reduction strategies, not from splitting
([Thinking Machines](https://thinkingmachines.ai/blog/defeating-nondeterminism-in-llm-inference/)).
At batch size 1, greedy decoding, same weights, same quantization, same kernels and a non-lossy
boundary dtype, bit-identical output is *expected*.

Required wording: *"Layer-wise splitting is quality-neutral when every node runs the same
quantization and the same kernels and the activation crossing the link is not re-quantized. Measured
on a dense 1.7B, n=15, temperature 0, batch size 1. Not yet shown for the 30B MoE."*

**And n=15 is a demo, not a benchmark.** Wilson 95% CI for 7/15 is **[24.8%, 69.9%]**. Report the
*equivalence* result and explicitly decline to give an absolute quality number until ≥200 tasks or a
standard benchmark. Pre-empting this is worth more than the number.

## 5.3 The planner's flagship feature destroys the planner's proof

The brief wants per-layer precision selection per device (§4.4). That is not numerical noise — it is
a **different model**. Layer quantization sensitivity varies by orders of magnitude and early-layer
error compounds forward ([MXSens](https://arxiv.org/pdf/2607.17733)).

The moment the planner may choose bit-widths, "splitting is quality-neutral" stops being something we
have measured. **Recommended product split:** Tier 1 ships uniform quantization, layer split only —
the configuration we can prove. Tier 2 gates mixed precision behind a measured per-plan quality
certificate. Do not let the planner emit a mixed-precision plan it has never evaluated.

This is a genuinely good framing for the pitch: *the constraint is the engineering result, not a
limitation.*

## 5.4 Thermals are the dominant cost term, not a safety limit

Our own curve: 11.44 → 5.29 tok/s, a factor of **2.16**, in ten minutes.

- Cost per token rises **2.16×** within ten minutes of a job starting. A planner pricing at 11.44
  tok/s is wrong by 116%.
- Over an hour (linear decay for 10 min, then steady): ~20,892 tokens/hour = **5.80 tok/s average =
  50.7% of peak.** Any hour-scale capacity quote off peak is ~2× optimistic, and sustaining peak
  aggregate throughput needs **2.16× the device count** — a capex multiplier that belongs in the
  objective function.
- Not device-specific bad luck: Snapdragon 8 Elite-class stability is 77.9–83.3% on 3DMark Wild Life
  Extreme with a documented worst case of 46.8%. LLM decode is harsher than a graphics loop.
  MELTing Point (MobiCom'24, [arXiv 2403.12844](https://arxiv.org/abs/2403.12844)) concludes
  continuous on-device LLM execution "remains elusive" on energy and thermal grounds.

**The honest consequence we must state ourselves:** at ~9 W and 5.29 tok/s the sustained point is
≈1.70 J/token versus ≈0.79 J/token unthrottled — throttling roughly **doubles** J/token. Against the
3× deficit to batched server inference ([2609.11940](https://arxiv.org/abs/2609.11940)), the
throttled operating point is ~6× off a batched GPU. **The phone fleet's advantage is never energy
efficiency.** It is hardware acquired at salvage prices, data locality, and capacity where GPUs are
unavailable. Claim those three; the measurement contradicts the fourth.

**The planner feature that falls out of this, and it is a good one:** replace `if temp > limit: abort`
with `cost_per_token = (amortization + power × W) / throughput(thermal_state(t))`, driven off
`getThermalHeadroom()` *trend* (headroom 1.0 already means THERMAL_STATUS_SEVERE, so it lags). Then
**duty-cycle across a larger pool** — run each device in its high-throughput window and rotate, which
is strictly cheaper per token than running fewer devices hot. That is a defensible planner feature
derived directly from our own measurement, and a far better story than respecting a temperature limit.

**Battery wear, stated honestly:** at 42 °C and high state of charge we are in BU-808's worst
quadrant — 40 °C/100% leaves 65% capacity after a year vs 80% at 25 °C/100% and **85% at 40 °C/40%**
([BU-808](https://batteryuniversity.com/article/bu-808-how-to-prolong-lithium-based-batteries)). But
**do not monetise it**: at 5.29 tok/s a device emits ~167 M tokens/year, so a ₹2,000 battery is
~₹0.01 per million tokens. The real cost is maintenance labour and swelling/fire risk in dense racks.
Free win: **operate racked devices at 40–60% SoC on external power**, which more than halves annual
fade at 40 °C.

## 5.5 Trust: custody does not solve verification, it makes verification unnecessary

**Verifiable inference on untrusted hardware does not exist at our scale.** Published state of the
art proves **GPT-2** (0.1B): zkGPT in <25 s, 279× over prior work
([USENIX Security '25](https://www.usenix.org/conference/usenixsecurity25/presentation/qu-zkgpt)).
We run 30B. zkML circuit expansion is 10³–10⁴.

The obvious fallback — redundant execution — also fails, and for a reason that cuts at our product:
BOINC had to invent **homogeneous redundancy**, partitioning hosts into hardware/software equivalence
classes, because different machines return different-but-valid floating-point results
([arXiv 1903.01699](https://arxiv.org/pdf/1903.01699)). **Heterogeneity and exact-match verification
are mutually exclusive**, and heterogeneity is the product. What remains is tolerance-based
validation with a derived per-layer epsilon, plus expert-selection agreement for MoE.

TEE attestation does not close it either: Android key attestation and Play Integrity attest *which
software booted*, not that the arithmetic was right, and AVF/pKVM does not give attested GPU/NPU
execution — so confidential compute on a phone is effectively CPU-bound. If we cite Acurast
(270,000+ devices, 175+ countries, [arXiv 2503.15654](https://arxiv.org/html/2503.15654v2)), cite it
honestly: strong evidence that **phone fleets at scale are operable**, no evidence that
**computation is verifiable**.

**Privacy: "we only send hidden states, not text" is not a defensible claim.** ActInv reconstructs
client inputs from intermediate activations with high fidelity and survives noise injection and
sparsification ([CCS'26](https://arxiv.org/abs/2605.23158)); embedding inversion recovers **92% of
32-token inputs exactly** ([vec2text, arXiv 2310.06816](https://arxiv.org/pdf/2310.06816)). The
attack needs a *malicious node*, not an eavesdropper — so the mitigation is **node trust**, which is
precisely the custody argument. Keep tokenizer, embeddings and the LM head on the device we control
so a remote node never sees tokens or logits. We already do this
(`EngineArgs.kt:8` pins `output|output_norm|token_embd` to the host) — that is now a *security*
feature, not just a memory one.

**What custody removes** (the adversary class "self-interested device owner" ceases to exist): result
forgery for payment; Sybil and spoofed capacity — exactly io.net's **~1.8 million fake GPUs**
incident; Byzantine returns; activation inversion as an external threat; churn as an adversarial
process; residential-ISP terms exposure and CGNAT reachability; and the whole India TDS/GST/gig-worker
payee problem.

**What custody leaves:** the customer must trust us — which is the ordinary cloud trust model, a
solved *commercial* problem (contracts, SOC 2, DPDP processor agreements), not a cryptographic one.
Plus silent hardware corruption, which custody does **not** remove: Google's "mercurial cores" at a
few per several thousand machines, Meta/Athens reporting defect rates on the order of 1 in 1000
chips — and **phones have no ECC**, strictly worse than those server fleets. Mitigation is a
known-answer canary layer with a pinned expected output hash, quarantine on mismatch. Cheap, and a
good answer to "how do you know the hardware is right."

**The line to use:** *verifiable inference on untrusted hardware is unsolved — the state of the art
proves GPT-2 and we run a 30B MoE. So we don't ask anyone to trust untrusted hardware. We take
custody, own the trust boundary, and inherit the trust model every cloud already uses.*

## 5.6 Network is the dominant failure mode, and it argues for racks

Ours: TTFT **1.1 s** on USB tether (2–3 ms) versus **94 s and then cut off** on venue Wi-Fi
(13–181 ms, worst 10.5 s). An 85× degradation followed by total failure. A layer-split model is
latency-multiplied: every token crosses every boundary.

Demo on USB or an AP we control, **and say why** — this turns our worst measurement into the argument
for custody. In product, RTT becomes a hard admission-control gate measured continuously, which the
planner already half-implements at `host.rs:321` (refuses helpers above 60 ms).

## 5.7 India legal and tax — custody avoids nearly all of it

Not legal advice; the agent labelled each item. The structural finding is what matters: **the custody
model avoids essentially this entire section.** Buying or leasing hardware means no payee
relationship with thousands of individuals, no GST-on-individual-supply question, no gig-worker
aggregator question, no residential-ISP question.

The at-home model creates all of them at once:
- **Payment characterisation is genuinely arguable** (s.194-I rent on plant/machinery at 2%, threshold
  now ₹50,000/month after Finance Act 2025; s.194J technical services 2%; s.194C contractor; possibly
  s.194-O if we are an e-commerce operator) and we carry the risk of choosing wrong. At Honeygain-like
  economics ($1–3/device/month) almost every payee sits below every threshold — but **s.206AA forces
  higher withholding without PAN**, so collecting and validating PAN from tens of thousands of
  micro-payees is an unmodelled operational cost.
- **GST:** individuals won't cross the ₹20 lakh services threshold, so they'll be unregistered, which
  pushes the question to us via reverse charge. Whether "individual supplying compute" is a notified
  RCM category is uncertain (probably not). Gotcha: anyone liable under RCM must register *regardless*
  of threshold — so if RCM ever applied we would have created a registration duty for individuals.
- **Gig-worker exposure is underrated:** the Code on Social Security 2020 obliges aggregators to
  contribute 1–2% of turnover (capped at 5% of amounts paid) to a Social Security Fund, and Rajasthan's
  2023 Act is already live with a per-transaction cess. Whether a person whose *phone* works is a "gig
  worker" is novel, and that novelty is itself the risk.
- **DPDP Act 2023 + Rules 2025** (notified 13 Nov 2025; security-safeguard obligations phase in around
  13 May 2027): Rule 6 requires demonstrable encryption, controlled access, continuous logging, and
  **one-year log retention**. "Controlled access to systems" is very hard to evidence for a device in a
  home we cannot audit — especially when the activations we send are invertible to the original text.
  **The at-home model looks hard to defend under Rule 6; custody is straightforwardly defensible.**
  A second, independent argument for custody that enterprise buyers will grasp immediately.

## 5.8 Consumer-supply precedents

- **io.net:** ~1.8 million fake GPUs attempted to connect; the CEO called it a painful lesson.
- **Storj:** cut node payout rates three times in 2023, citing erasure-coding expansion and repair traffic.
- **SaladCloud:** nodes "interruptible by default", >90% stable, mean availability >99% with a measured
  **minimum of 94%**. A 94% worst-case node across a 3-node split implies ~17% chance of a degraded
  token stream per unit time under independence — and statefulness makes churn far costlier for us than
  for Salad (stateless batch) or Storj (erasure-coded storage).
- **Honeygain/proxyware:** Cisco Talos documented malware bundling a patched Honeygain client with a
  miner and an info-stealer. Earnings of $1–3/month select for bulk and fraudulent operators — exactly
  the supply we don't want.

**Consequence:** do not build the at-home variant for latency-sensitive inference. Consumer supply
suits stateless, retryable, batch work. Keep interactive on custody hardware.

## 5.9 Not completed

The session's web-search budget was exhausted. Unverified and not to be put on a slide: Helium
earnings-collapse figures, Gensyn/Prime Intellect current verification status, and verbatim Indian ISP
server-hosting clauses (only Airtel's "personal and non-commercial use" / 300 GB-per-30-days
commercial threshold was retrievable). The stream 6 patent sweep was not run for the same reason.

---

# Stream 2 — economics

Full agent report (62 KB, every figure URL-dated) preserved at
`~/.claude/projects/-home-yuvaraj-ambati-IQOO/b3f79ee8-.../tool-results/toolu_012TLKESRT3hknNrgrKzx8yu.txt`.

## 2.0 Corrections to things already said

- **I told the user "Cast AI charges on cluster savings." That is false.** Cast AI charges
  **$0.00694444 per managed-vCPU-hour (~$5.07/vCPU/month)** plus a $200/$1,000/$5,000 monthly tier —
  resources under management, not savings (primary: AWS Marketplace listing). Corrected to the user.
- **ProsperOps' 30–35% is an estimate, not a disclosed rate.** The gainshare *model* is primary
  ("a small percentage of the realized savings… not a percentage of your cloud spend"); the
  percentage comes from a single partly-auto-generated buyer guide. Say "undisclosed; comparables
  suggest 20–35%".
- **The doc's model list is a generation stale.** Last open-weight Llama was Llama 4 Scout/Maverick
  (5 Apr 2025); Meta's Apr 2026 flagship **Muse Spark is not open-weights**. Qwen is at 3.8. Groq has
  moved Llama 3.1/3.3 to "Contact Sales" with no per-token rate. Pitching optimisation of "Qwen3 8B"
  prices a model most providers no longer sell.
- **USD/INR is ~96.5, not 88–89.** Any rupee figure built on 88 understates by ~9%.
- Also wrong in circulation: Cloudability is IBM/Apptio not Flexera; Spot.io is Flexera not NetApp;
  "Flexera acquired ProsperOps" is unverified — do not repeat.

## 2.1 The gainshare business does not close. Five numbers.

1. **$1.40** — the entire monthly API bill for the brief's own stated workload (50M in + 10M out,
   8B-class, at DeepInfra rates). Revenue at 10% of a *total* saving: **14 cents/month.**
2. **2.49 billion output tokens/month** — the volume needed merely to *saturate one A10G*, below which
   self-hosting loses to the API outright. 249× the brief's workload. Even there the savings pool is
   **$49.81/month** and the fee **$4.98**.
3. **$830 vs $651** — in the better 30B case, self-hosting *with the N+1 redundancy spot instances
   actually require* costs **more than the API**. The optimiser's honest recommendation becomes "delete
   the infrastructure you hired us to optimise." There is no savings stream in that answer.
4. **5.3 years** — payback on one customer's 2–4-week shadow-mode onboarding (~$1,490 of engineer time
   at Indian rates, agent's estimate) against $283/yr of fee revenue. **Minimum viable customer at 10%
   is ~17 continuously-busy A100s — roughly a billion self-hosted output tokens a day.**
5. **~$13.5M/year** — the entire *global* revenue pool for this fee model across all vendors, via a
   labelled chain from Menlo's $37B enterprise gen-AI spend → $1.5B "AI infrastructure" → ~$900M
   inference serving (estimate) → 15% achievable savings → 10% fee. India's slice: a few hundred
   thousand dollars. **That is a feature inside someone's FinOps platform, not a company.**

**Structural finding: not one vendor anywhere charges a percentage of verified savings on inference.**
Everyone who genuinely optimises the serving runtime monetises by **selling compute** — NVIDIA Dynamo,
vLLM, SGLang and KAI Scheduler are $0; Red Hat AI Inference Server is "priced per accelerator";
Baseten and Fireworks charge per-token plus per-GPU-hour. Gainshare in FinOps is confined to
*commitment* arbitrage (Usage.ai 20/35%, Zesty 25% + $250/mo — both primary-sourced from Marketplace
metering dimensions).

Adjacent gainshare bands run **20–50% for a fixed 12–60 month term; nobody charges in perpetuity.**
10% sits at the floor, the rate paid by the most sophisticated institutional buyers (Medicare RAC
9–12.5%). The one company that priced at exactly 10% — Antimetal — no longer publishes it and has
pivoted off cost gainshare.

Plus three contract-level killers: **inference prices deflate ~an order of magnitude a year**, so a
gainshare baseline collapses on its own and you end up invoicing for market deflation; the measurement
denominator used in practice (ProsperOps' ESR) is **list price**, so it cannot attribute savings to the
vendor at all; and free native tooling keeps eating the overlap.

## 2.2 The phone tier loses, and the binding cost is batteries — not electricity

**The break-even rule** (per million output tokens; `E` J/tok, `p` $/kWh at the wall, `C` asset cost,
`N` cycle life, `B` battery Wh, `r` owner reward $/device-hr, `T` *sustained* tok/s, `G` spot $/hr,
`Q` cloud tok/s):

> include the phone iff **(E/3.6)·[ p + (1000/B)·(C/N) ] + 277.78·(r/T) < 277.78·(G/Q)**

**Evaluated in the most generous honest configuration** — E=0.65 J/tok, ₹6.52/kWh, 19.25 Wh battery,
1,000 cycles, **C=$31 (broken farm phone)**, r=$0.00184/hr (Acurast's actual rate), **T=5.29 tok/s (our
own measured sustained rate)**, against an A10G at spot $0.4093/hr and 947 tok/s:

| Term | $/M output tokens |
|---|---|
| Electricity | **0.0152** |
| Device amortisation (9.38 full battery cycles per million tokens) | **0.291** |
| Owner reward | **0.0966** |
| **Phone total** | **0.403** |
| **Cloud spot** | **0.1201** |

**The phone loses by 3.4× at its best, and 14–82× with a phone anyone cares about.** Solving for the
asset cost that would make it win: **C < $0.885.** There is no such phone.

**Electricity is never the binding term — 1.5 cents per million tokens.** The phone does not lose on
power. It loses on the phone. That is the single most important correction to the whole thesis.

Five independent confirmations the refusal is robust:

1. **Thermals cap a phone at 5–10 queries/hour** before throttling dominates (independent study:
   iPhone 16 Pro −44%, Galaxy S24 Ultra *terminated* at a 231 MHz GPU floor; an RTX 4050 laptop decayed
   only −7.4%). Our own −54% is squarely in family. At ~500 tokens/query that is 2,500–5,000 tok/hr per
   phone against **3.4 million tok/hr from one A10 — so one A10 ≈ 680–1,360 phones.**
2. **A hardware-instrumented peer-reviewed study already ran the sensitivity analysis and found no
   escape:** break-even requires **10⁵–10⁶ charge cycles against a real 500–2,000**, and *"above the
   world-average grid intensity, no finite endurance suffices."* 88–90% of local impact is embodied
   manufacturing, not electricity.
3. **The phone is anti-synergistic with batching**, which is where cloud cheapness comes from. The
   $0.12/M figure assumes concurrency 64; a phone serves ~1. Every request moved to a phone is removed
   from the batch, *raising* the cost of the remaining cloud work.
4. **The network failure we measured is the category's documented failure.** Petals reaches ~6 tok/s on
   70B with churn and bandwidth as structural limits. A researcher could not get a **0.5B** model to
   run on Acurast across 8 mainnet + 2 canary deployments.
5. **The reward market is far below viability and the networks know it.** Acurast pays **$1.34 per
   device per month, 100% from token inflation with no published customer revenue**; only ~15% of its
   marketed 250,000 phones actually share compute. Community: *"I shut down my phone farm"*; *"20
   phones, 3 of them have a swelled battery"* — a 15% hardware failure rate.

## 2.3 What *does* work — and it is not phones, and not gainshare

**$ per GB of accelerator-memory-hour** (the agent notes no published analysis computes this metric;
treat as original work):

| What | VRAM | $/GB-hr |
|---|---|---|
| **Owned used RTX 3090, 3 yr @ 100% duty, India power** | 24 | **0.00319** |
| Owned used RTX 3090, **2 yr @ 50% duty** | 24 | 0.00835 ← advantage gone |
| Azure A100 spot, eastus | 80 | 0.00848 |
| Azure A100 spot, centralindia | 80 | 0.01188 |
| AWS A10G spot, us-east-1 | 24 | 0.01705 |
| Fireworks H100 dedicated | 80 | 0.10000 |

**The legitimate local tier is idle owned consumer GPUs, not phones** — 2.2–2.6× cheaper per GB-hour
than the best verified hyperscaler spot. **Duty cycle is the whole ballgame:** at 2 years and 50% duty
the advantage vanishes entirely.

**Licensing caveat:** NVIDIA's GeForce driver EULA §2.8 says GeForce software *"is not licensed for
datacenter deployment."* It binds the driver, not the silicon — fine on an employee's own workstation,
which is exactly the mesh's legitimate case; **not fine in a rack.** A question for counsel. Vast.ai
and Salad being peer-to-peer marketplaces rather than datacentre operators is the market's answer.

**The niche where the economics close:**

> A **fixed-fee, per-accelerator** inference-efficiency engagement for Indian organisations
> **legally barred from commodity APIs** — BFSI under RBI data-localisation, healthcare, government,
> defence, and IndiaAI-Mission-subsidised public compute — where the comparison is not "API versus
> self-host" but **"badly-run self-host versus well-run self-host."**

Why this one: the customer **cannot defect to the cheaper option**, which is the only condition under
which the API floor stops capping your value (McKinsey: those preferring proprietary cite
security/risk/control 72% of the time; CNCF names regulatory prohibition as one of only three
conditions that flip the self-host calculation). The waste is real and large — 59% of compute wasted
across 122K jobs on an instrumented national HPC cluster; Banana at ~20% aggregate utilisation;
Flexera 2026 reporting wasted cloud spend up 29%. And the policy tailwind is quantified: **IndiaAI has
onboarded 34,381–38,000+ GPUs from 14 empanelled providers, ₹10,371.92 crore, subsidised to ≈₹65/GPU-hr
(~$0.674)** — roughly a quarter of Western on-demand — **with no FinOps layer on top of them.** The only
buyer pool found that is both large and structurally unable to use an API. (Government figures via
secondary press; the PIB primary release and tender rate sheet were not retrieved.)

**Price per accelerator, not on savings:** it survives the deflation problem that destroys an inference
gainshare baseline, and it gets paid *during* the observation window instead of after it.

## 2.4 Two more levers the brief gets wrong on the merits

- **Prefill/decode disaggregation is a ~1,000-GPU problem.** Below that chunked prefill is the better
  default; at 8–16 GPUs specialisation gains are eaten by incomplete worker utilisation, each request
  moves ~2.6 GB of KV cache, and **small or untuned deployments see a 20–30% performance drop.** vLLM's
  own docs note it does not inherently improve throughput. Chunked prefill gives +50% and is free.
- **Quantization can go the wrong way on cost.** Best-documented 2026 example: Kimi K3 1-bit on 8×A100
  at 2.8× lower $/hr produced **3.3× higher $/token** than native MXFP4 on 8×B300. Quality was fine;
  throughput killed it. **Quantization buys fit, not necessarily cost** — which is exactly the right
  framing for our memory thesis.

## 2.5 The honest reframe

**State the refusal as the feature.** A planner that says *"we evaluated your 240 corporate Android
devices and the answer is no — here is the arithmetic"* is more credible than one claiming a phone
cluster is a profit centre. Note the trap that closes the last door: the phone's only tolerable
workload is latency-insensitive batch, but **Batch APIs are already 50% off** at OpenAI, Anthropic,
Fireworks and Together — so the price a phone must beat *halves* exactly in the regime where a phone is
usable.

## 2.6 Research limits — state if challenged

Search budget exhausted partway; later work was direct-fetch only. Not obtained: the MERC tariff PDF
(₹6.52 is from summary sites), official Samsung/Xiaomi/OnePlus India battery pricing (only OnePlus's
₹2,599 plan verified — **any "₹1,500–5,000" range is unsourced**), measured sustained 3090/4090
inference wattage (280 W/350 W are estimates), used A100 street price, Indian used-GPU prices in INR,
IDC/Gartner/Omdia (403), the **Menlo 2026 enterprise edition (not yet published; due Nov/Dec 2026 and
it will be the first read on open-weight share after Llama's discontinuation)**. Do **not** cite: the
"60% vs 85% utilised fleet" line (vendor illustration, not survey data), nordiccrypto.io's Acurast
earnings table (affiliate-incentivised), or the dev.to "I went back to APIs" posts (SEO cluster). **No
published postmortem from a named company that shut down a GPU cluster and returned to APIs exists.**

## 2.7 Addendum — the growth-side evidence, and why it sharpens the niche

A later sweep filled the gap flagged in 2.6. **It reverses no arithmetic** — the 10% fee and the phone
tier still fail — but it makes the regulated/sovereign niche the best-evidenced conclusion found.

**The key distinction: growth evidence is strong on supply and infrastructure, and essentially absent
on enterprise demand. That gap is itself the finding.**

### Supply side — real, large, fast (the honest counter-case)

- GitHub: ollama 182,434 stars; llama.cpp 130,551; vLLM 93,312 (from 32,600 in Jan 2025 — 2.86× in
  ~21 months); SGLang 36,832.
- Docker pulls: ollama/ollama **183.8M**; vllm-openai 37.9M; sglang 14.0M.
- Hugging Face: **209,551 GGUF repos** (the local llama.cpp/Ollama format) growing ~278/day, doubling
  in ~2 years; **25,472 MLX repos** growing ~88/day — **more than doubling within a year**, the fastest
  relative growth measured anywhere. Quantization republishers are pure self-hosting demand: unsloth
  60.6M and bartowski 14.3M 30-day downloads (both truncated floors).
- vLLM in production: *"500K+ GPUs deployed 24/7", "200+ accelerator types"* (Red Hat, attributed to
  vLLM Office Hours #38, 18 Dec 2025 — primary recording not retrieved).
- **On-prem OEM channel, from SEC filings — the hardest numbers here.** Dell AI-optimised server
  revenue FY2025 $9.29B → FY2026 $24.68B (+166%); Q2 FY27 $16.4B with **backlog going $43B → $95B in
  two quarters**; FY27 guidance raised to $60B. Supermicro FY2024 $14.99B → FY2026 $39.06B. HPE Cloud &
  AI op margin 7.0% → 17.0% Y/Y. *Caveat: Dell does not split hyperscaler vs neocloud vs enterprise —
  this is on-prem-channel evidence, not pure enterprise on-prem.*
- **NVIDIA restructured its segment reporting to isolate exactly this question**, splitting Data Center
  into Hyperscale and **ACIE** ("AI Clouds, Industrial and Enterprise"). A company that size changing
  segment reporting to break out non-hyperscale AI is itself evidence. **But the actual dollar split
  could not be retrieved** — investor.nvidia.com returned HTTP 429 — and that is the one number that
  would settle enterprise-vs-hyperscale directly. Nobody outside NVIDIA has it.

### Demand side — the weak leg, and it partly cuts against us

**No survey with a named rising self-host-intent percentage was found, and no regulated-industry
on-prem mandate text.** The one survey obtained is net-sceptical (a16z, 100 CIOs, May 2025): on-prem
preference is higher at larger enterprises, but **23% were already running OpenAI o3 in production
versus 3% for DeepSeek**, and open models were chosen "mainly for highly cost-sensitive use cases."
**Do not cite it as rising self-host intent.** Best remaining lead is registration-gated (NVIDIA "State
of AI in Financial Services" 2026, n>800, with an on-prem section).

### The reconciliation — this is the useful part

Open-weight **consumption** is booming while open-weight **self-hosting by enterprises** is not taking
spend share. Growth concentrates in three places and **only the third is a buyer**:

1. **Developers and local runtimes** — millions of installs, almost no enterprise spend attached.
2. **Third-party serverless providers reselling open weights** (Fireworks ~$1B ARR, Groq 6M devs,
   Together's $800M raise) — **competitors, not customers.** They employ the people who wrote the
   optimisers.
3. **Sovereign and regulated-industry programmes where on-prem is a procurement requirement rather
   than an economic choice** — and this category is genuinely expanding fast: Korea 260,000 GPUs; UK
   £2B NVIDIA investment plus BT/Nscale to 65 MW; 35 new NVIDIA AI supercomputers across Europe;
   Saudi/HUMAIN to 1 GW by 2030; Deutsche Telekom up to 10,000 GPUs; Japan's national AI infrastructure.

**The two most load-bearing quotes in the entire research**, both from NVIDIA's UK sovereign-AI post
(7 Jun 2026):

> *"Sovereign AI is most impactful at the inference layer"* — Meryem Arik, CEO of Doubleword, a
> self-hosted-inference vendor
> *"Sovereignty is actually now a buying criterion"* — Talfan Evans, CEO of Cursive

**Note what they name: compliance, not cost.** Consistent with every survey in the main report (a16z
ranks control first and cost third; McKinsey finds security/control cited by 72% of
proprietary-preferrers, and security/compliance the top barrier at 56%).

**The message therefore is not "we save you money." It is "you cannot legally use the API, so let us
make the infrastructure you are required to own actually efficient."**

### India — now the strongest case in the research

From NVIDIA's IndiaAI post (17 Feb 2026): IndiaAI Mission *"infusing India's AI ecosystem with over $1
billion"* and *"tens of thousands of NVIDIA GPUs"*; **Yotta Shakti Cloud "powered by over 20,000
NVIDIA Blackwell Ultra GPUs"** (Navi Mumbai + Greater Noida), explicitly *"designed to make advanced AI
training and inference affordable and compliant for Indian enterprises and public sector customers"*;
L&T building *"sovereign, gigawatt-scale"* AI factory infrastructure (Chennai 30 MW, Mumbai 40 MW);
GB200 NVL4 manufactured in India by Netweb; Sarvam's Pravah for government inference; Gnani reporting
a *"15× reduction in inference costs"* at >10M calls/day.

Against the price gradient: **subsidised IndiaAI H100-class hours at ≈₹92 / $0.95 sit 2.5–4× below
every Indian commercial rate** (Krutrim $2.21, E2E $2.69, Yotta $3.64, Neysa $4.39, Tata $5.06) and
below most Western on-demand rates.

### The sharpened conclusion

**A subsidy-driven sovereign buyer is a different customer from a cost-optimising enterprise, and it is
the one actually growing.** For that buyer the API is not an option, the subsidy means the GPUs already
exist and are already paid for, and the question is not "self-host or not" but **"is this subsidised
fleet being wasted."** That is where the measured 10–20% from idle-GPU reclamation, batching and
utilisation meets a customer who cannot walk away.

**And it is the strongest argument yet for per-accelerator pricing:** on subsidised compute the savings
baseline is policy-set and partly fictional, so a share of it is **unauditable** — whereas "₹X per GPU
per month to keep this fleet efficient" is a line item a public-sector procurement office can sign.

### Methodological warnings — repeat if challenged

- **Do not cite vLLM PyPI downloads as growth.** May→Aug 2026 they fell **30%** (201,261 → 141,396),
  and −56% from April, while every other package rose. The decline is real within the comparable
  window; cause undocumented. "Channel shift to containers" is a hypothesis, not a fact. SGLang PyPI
  data is unusable (mirror/CI contamination). GitHub's contributors API saturates at ~500, so
  contributor counts are floors.
- **Do not cite two Red Hat figures:** the "24× higher throughput" claim footnotes to the original
  **June 2023** PagedAttention post, ~3 years stale; the "233% ROI over 3 years" is a **Forrester TEI
  commissioned by Red Hat** on composite-organisation methodology.
- **NVIDIA NIM's widely repeated $4,500/GPU/year could not be confirmed** (pricing URL 404s). The AWS
  Marketplace figures for NVIDIA AI Enterprise are **hourly per-running-GPU metering** — a different
  SKU; do not present it as the annual subscription.
- **Sovereign-AI revenue is never attached to a number** in any of NVIDIA's four quarterly releases —
  "sovereign" appears only in partnership highlights.

### Remaining gaps, priority order

1. NVIDIA's **Hyperscale vs ACIE dollar split** (Q2 FY27 CFO commentary) — settles enterprise-vs-hyperscale.
2. The gated **NVIDIA FSI 2026 survey** (n>800) for regulated-industry on-prem percentages.
3. A quantified **inference-vs-training spend split** — not found by any of seven agents. **Do not assert one.**
4. A verified **enterprise GPU-utilisation figure** — still none. The circulating "60% vs 85%" line is
   vendor illustration in a blog post, not data.
5. The IndiaAI tender notification for official per-GPU-hour rates (indiaai.gov.in is a JS SPA).

## 2.8 Addendum 2 — spend composition from primary sources

A sub-stream returned with SEC-filed and first-party survey data. **Confirms 2.1–2.7; adds one
decisive framing and two headwinds.**

### The sentence that matters most: preference far exceeds dollars

| Measure | Value | Source |
|---|---|---|
| Enterprise leaders who **prefer** hosting on own infrastructure | **40%** | McKinsey/Mozilla/McGovern, Apr 2025, n=703 |
| Who regularly use open source at the **hosting/inference-compute layer** | **32%** | same |
| Who **self-host** something | **>25%** | a16z, Mar 2024, n=70 Fortune 500 |
| Share of enterprise gen-AI **spend** in the bucket closest to self-hosted serving | **$1.5B of $37B ≈ 4%** | Menlo, Dec 2025 |

**40% want to self-host; ~4% of the money is there.** That gap is the whole opportunity and the whole
risk at once: the intent is real and large, the budget has not followed. A product sold on *cost* is
fighting the 4%; a product sold on *compliance* is addressing the 40%. This is the cleanest numerical
support for the pitch flip in 2.7.

*The three anchors above measure different things — do not blend them into one percentage.*

### Open-weight share of the enterprise market is shrinking, not growing

Menlo's own consistent time series: **~20% (2023) → 19% (2024) → 13% (mid-2025) → 11% (end-2025).**
Chinese open models ≈1% of enterprise usage. Stated reason for the mid-2025 drop: "performance gaps
widened." Anthropic 40% + OpenAI 27% + Google 21% = 88% of enterprise LLM API spend.

**Build vs buy is moving against us too: 53% purchased (2024) → 76% purchased (2025).**

### Where the capital actually is

Big-4 hyperscaler **total** capex ≈ **$357.6B FY2025** (Amazon $131.8B CY2025 and $173.0B LTM to Jun
2026; Microsoft $116.0B FY26; Alphabet $80.6B H1 2026 alone; Meta $69.7B CY2025) — against Menlo's
$18B *entire* infrastructure layer. CoreWeave Q2 2026 revenue **$2,575M (+112%)** with **$103.7B
contracted backlog**; Nebius Q2 **$582.3M (+454%)**.

**Almost all capital going into inference capacity is spent by model and cloud providers, not by
enterprises buying their own GPUs. Enterprises rent the output.** This is the structural reason the
gainshare pool is small.

### Two headwinds to state honestly

1. **Fine-tuning is becoming less central** (a16z, Jun 2025): improved base models have "made
   fine-tuning less critical," prompt engineering gets similar results "often at much lower cost," and
   fine-tuned models carry "high upfront costs." Fine-tuning was a main historical reason to self-host;
   that reason is weakening.
2. **Reasons to self-host are control first, cost second — consistently.** a16z ranks control →
   customization → cost, "cost explicitly ranked lower than expected." McKinsey: proprietary-preferrers
   cite security/risk/control **72%** of the time; the top *barrier* to open-source AI is security and
   compliance (**56%**). Anyone pitching savings is pitching the third-ranked reason.

### Supporting detail worth having

- AI spend has moved from experiment to line item: innovation budgets fell from **25% → 7%** of LLM
  spend between mid-2024 and mid-2025 (a16z); Menlo had it 60/40 innovation/permanent in 2024.
- McKinsey measured outcomes: open source showed a cost-savings edge averaging **4% higher** than
  proprietary, typical cost improvement 26%. **75%** expect to increase open-source AI use.
- Bessemer's highest-growth AI cohort runs **~25% gross margins, often negative** — the strongest
  evidence that inference COGS dominates for AI-native vendors, though Bessemer never quantifies it.

### Do not claim / verify first

- **No published estimate anywhere splits inference spend between API resale and self-hosted
  inference.** Menlo's $1.5B is the nearest proxy and is not defined that way. If we need the split we
  must construct it and label it our own estimate.
- **No credible survey gives "% of companies running open-weight models in production on owned GPUs."**
- **Menlo restated 2024 total spend from $13.8B down to $11.5B** — flag this if quoting a growth rate.
- The widely circulated **"$20/M → $0.07/M tokens, 280× in 18 months"** inference-cost figure is
  attributed to Stanford's AI Index 2025 everywhere but **did not appear on the chapter page fetched.**
  Verify against the PDF before using it.
- **Zero FinOps Foundation data was obtained** (403 on every path). Any claim about AI's share of cloud
  spend from that source is unsupported.
- No AI-only capex figure exists in any public filing; the hyperscaler numbers above are total capex.
